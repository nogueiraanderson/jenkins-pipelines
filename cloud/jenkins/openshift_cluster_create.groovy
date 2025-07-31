// groovylint-disable-next-line UnusedVariable, VariableName
@Library('jenkins-pipelines') _

pipeline {
    agent any

    environment {
        WORK_DIR = "${WORKSPACE}/openshift-clusters"
        S3_BUCKET = 'openshift-clusters-119175775298-us-east-2'
    }

    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '100', daysToKeepStr: '30'))
        timestamps()
    }

    stages {
        stage('Prepare') {
            steps {
                script {
                    // Clean workspace
                    deleteDir()

                    // Create work directory
                    sh "mkdir -p ${WORK_DIR}"

                    // Set cluster name with timestamp if needed
                    if (params.CLUSTER_NAME == 'test-cluster') {
                        env.FINAL_CLUSTER_NAME = "test-cluster-${BUILD_NUMBER}"
                    } else {
                        env.FINAL_CLUSTER_NAME = params.CLUSTER_NAME
                    }

                    // Sanitize cluster name
                    env.FINAL_CLUSTER_NAME = env.FINAL_CLUSTER_NAME.toLowerCase().replaceAll(/[^a-z0-9-]/, '-')

                    echo "Final cluster name: ${env.FINAL_CLUSTER_NAME}"
                }
            }
        }

        stage('Get Credentials') {
            steps {
                script {
                    // Get pull secret from credentials
                    withCredentials([
                        string(credentialsId: 'openshift-pull-secret', variable: 'PULL_SECRET')
                    ]) {
                        env.OPENSHIFT_PULL_SECRET = PULL_SECRET
                    }

                    // Get or generate SSH key
                    withCredentials([
                        sshUserPrivateKey(
                            credentialsId: 'openshift-ssh-key',
                            keyFileVariable: 'SSH_KEY_FILE'
                        )
                    ]) {
                        // Generate public key from private key
                        sh """
                            ssh-keygen -y -f ${SSH_KEY_FILE} > ${WORK_DIR}/ssh-public-key.pub
                        """
                        env.SSH_PUBLIC_KEY = readFile("${WORK_DIR}/ssh-public-key.pub").trim()
                    }
                }
            }
        }

        stage('Create Cluster') {
            steps {
                withCredentials([
                    aws(
                        credentialsId: 'jenkins-openshift-aws',
                        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                    )
                ]) {
                    script {
                        // Set debug mode if requested
                        if (params.DEBUG_MODE) {
                            env.OPENSHIFT_INSTALL_LOG_LEVEL = 'debug'
                        }

                        def clusterConfig = [
                            clusterName: env.FINAL_CLUSTER_NAME,
                            openshiftVersion: params.OPENSHIFT_VERSION,
                            awsRegion: params.AWS_REGION,
                            pullSecret: env.OPENSHIFT_PULL_SECRET,
                            sshPublicKey: env.SSH_PUBLIC_KEY,
                            s3Bucket: env.S3_BUCKET,
                            workDir: env.WORK_DIR,
                            baseDomain: params.BASE_DOMAIN,
                            masterType: params.MASTER_INSTANCE_TYPE,
                            workerType: params.WORKER_INSTANCE_TYPE,
                            workerCount: params.WORKER_COUNT.toInteger(),
                            deleteAfterHours: params.DELETE_AFTER_HOURS,
                            teamName: params.TEAM_NAME,
                            productTag: params.PRODUCT_TAG,
                            deployPMM: params.DEPLOY_PMM,
                            pmmVersion: params.PMM_VERSION,
                            buildUser: env.BUILD_USER_ID ?: 'jenkins',
                            accessKey: AWS_ACCESS_KEY_ID,
                            secretKey: AWS_SECRET_ACCESS_KEY
                        ]

                        // Create the cluster
                        def clusterInfo = openshiftCluster.create(clusterConfig)

                        // Store cluster info for post actions
                        env.CLUSTER_API_URL = clusterInfo.apiUrl
                        env.CLUSTER_CONSOLE_URL = clusterInfo.consoleUrl ?: ''
                        env.CLUSTER_DIR = clusterInfo.clusterDir
                        env.KUBECONFIG = clusterInfo.kubeconfig

                        if (clusterInfo.pmm) {
                            env.PMM_URL = clusterInfo.pmm.url
                        }

                        // Archive credentials with correct path
                        def clusterPath = "openshift-clusters/${env.FINAL_CLUSTER_NAME}"
                        if (fileExists("${clusterPath}/auth")) {
                            archiveArtifacts artifacts: "${clusterPath}/auth/**",
                                           fingerprint: true,
                                           allowEmptyArchive: false
                        } else {
                            error "Critical: Auth directory not found at ${clusterPath}/auth"
                        }
                    }
                }
            }
        }

        stage('Post-Creation Tasks') {
            steps {
                script {
                    // Display cluster information
                    echo """
                    ========================================
                    Cluster Creation Summary
                    ========================================
                    Cluster Name: ${env.FINAL_CLUSTER_NAME}
                    OpenShift Version: ${params.OPENSHIFT_VERSION}
                    AWS Region: ${params.AWS_REGION}
                    API URL: ${env.CLUSTER_API_URL}
                    Console URL: ${env.CLUSTER_CONSOLE_URL}
                    S3 Backup: s3://${env.S3_BUCKET}/openshift-state/${env.FINAL_CLUSTER_NAME}/
                    Delete After: ${params.DELETE_AFTER_HOURS} hours
                    ${env.PMM_URL ? "PMM URL: ${env.PMM_URL}" : ''}
                    ========================================
                    """

                    // Set build description
                    currentBuild.description = "${env.FINAL_CLUSTER_NAME} - ${params.AWS_REGION}"

                    // Add build parameters as properties for easier tracking
                    currentBuild.displayName = "#${BUILD_NUMBER} - ${env.FINAL_CLUSTER_NAME}"
                }
            }
        }
    }

    post {
        success {
            script {
                echo "Cluster ${env.FINAL_CLUSTER_NAME} created successfully"

                // Always archive kubeconfig on success as a fallback
                if (env.WORK_DIR && env.FINAL_CLUSTER_NAME) {
                    def criticalFiles = [
                        "${env.WORK_DIR}/${env.FINAL_CLUSTER_NAME}/auth/kubeconfig",
                        "${env.WORK_DIR}/${env.FINAL_CLUSTER_NAME}/auth/kubeadmin-password"
                    ]
                    criticalFiles.each { file ->
                        if (fileExists(file)) {
                            def relativePath = file.replaceFirst("${env.WORKSPACE}/", "")
                            archiveArtifacts artifacts: relativePath,
                                           fingerprint: true,
                                           allowEmptyArchive: false
                        }
                    }
                }

                // Send notification if configured
                if (env.SLACK_WEBHOOK) {
                    slackSend(
                        color: 'good',
                        message: "OpenShift cluster created: ${env.FINAL_CLUSTER_NAME} in ${params.AWS_REGION}"
                    )
                }
            }
        }
        failure {
            script {
                echo "Failed to create cluster ${env.FINAL_CLUSTER_NAME}"

                // Archive failure logs
                if (env.FINAL_CLUSTER_NAME) {
                    // Use full workspace path for artifacts
                    def clusterPath = "openshift-clusters/${env.FINAL_CLUSTER_NAME}"
                    archiveArtifacts artifacts: "${clusterPath}/**/*.log", allowEmptyArchive: true
                    archiveArtifacts artifacts: "${clusterPath}/**/log-bundle-*.tar.gz", allowEmptyArchive: true
                    archiveArtifacts artifacts: "${clusterPath}/terraform.tfstate", allowEmptyArchive: true
                    archiveArtifacts artifacts: "${clusterPath}/metadata.json", allowEmptyArchive: true
                    archiveArtifacts artifacts: "${clusterPath}/.openshift_install.log", allowEmptyArchive: true
                    archiveArtifacts artifacts: "${clusterPath}/install-config.yaml.backup", allowEmptyArchive: true

                    // Always try to archive auth files in failure
                    archiveArtifacts artifacts: "${clusterPath}/auth/**", allowEmptyArchive: true
                }

                // Attempt cleanup if cluster creation failed
                if (env.FINAL_CLUSTER_NAME) {
                    if (params.DEBUG_MODE) {
                        echo 'DEBUG MODE: Skipping automatic cleanup of failed cluster resources'
                        echo 'To manually clean up resources later, run the openshift-cluster-destroy job with:'
                        echo "  - Cluster Name: ${env.FINAL_CLUSTER_NAME}"
                        echo "  - AWS Region: ${params.AWS_REGION}"
                        echo 'Resources will remain in AWS for debugging purposes'
                    } else {
                        echo 'Attempting to clean up failed cluster resources...'
                        try {
                            withCredentials([
                                aws(
                                    credentialsId: 'jenkins-openshift-aws',
                                    accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                                    secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                                )
                            ]) {
                                openshiftCluster.destroy([
                                    clusterName: env.FINAL_CLUSTER_NAME,
                                    awsRegion: params.AWS_REGION,
                                    s3Bucket: env.S3_BUCKET,
                                    workDir: env.WORK_DIR,
                                    accessKey: AWS_ACCESS_KEY_ID,
                                    secretKey: AWS_SECRET_ACCESS_KEY
                                ])
                            }
                        } catch (Exception e) {
                            echo "Cleanup failed: ${e.toString()}"
                        }
                    }
                }

                // Send notification if configured
                if (env.SLACK_WEBHOOK) {
                    slackSend(
                        color: 'danger',
                        message: "Failed to create OpenShift cluster: ${env.FINAL_CLUSTER_NAME}"
                    )
                }
            }
        }
        always {
            script {
                // Clean up workspace but keep cluster state
                sh """
                    # Clean up temporary files
                    rm -f ${WORK_DIR}/ssh-public-key.pub || true
                """

                // Clear sensitive environment variables
                env.OPENSHIFT_PULL_SECRET = ''
            }
        }
    }
}
