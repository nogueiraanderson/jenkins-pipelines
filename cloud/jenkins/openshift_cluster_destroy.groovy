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
        stage('Validate') {
            steps {
                script {
                    // Validate cluster name is provided
                    if (!params.CLUSTER_NAME || params.CLUSTER_NAME.trim().isEmpty()) {
                        error 'Cluster name is required'
                    }

                    // Clean workspace
                    deleteDir()

                    // Create work directory
                    sh "mkdir -p ${WORK_DIR}"

                    echo "Preparing to destroy cluster: ${params.CLUSTER_NAME}"
                }
            }
        }

        stage('Check Cluster State') {
            steps {
                withCredentials([
                    aws(
                        credentialsId: 'jenkins-openshift-aws',
                        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                    )
                ]) {
                    script {
                        // Check if cluster state exists in S3
                        // Check if state exists by trying to get metadata
                        def metadataResult = openshiftS3.getMetadata([
                            bucket: env.S3_BUCKET,
                            clusterName: params.CLUSTER_NAME,
                            region: params.AWS_REGION,
                            accessKey: AWS_ACCESS_KEY_ID,
                            secretKey: AWS_SECRET_ACCESS_KEY
                        ])
                        // Convert LazyMap to regular HashMap to avoid serialization issues
                        def metadata = metadataResult ? new HashMap(metadataResult) : null
                        def stateExists = (metadata != null)

                        if (!stateExists) {
                            error "Cluster state not found in S3 for cluster: ${params.CLUSTER_NAME}"
                        }

                        // Get cluster metadata if available
                        // Metadata already retrieved above

                        if (metadata) {
                            echo """
                            Found cluster metadata:
                            - Created: ${metadata.created_date}
                            - Created by: ${metadata.created_by}
                            - OpenShift Version: ${metadata.openshift_version}
                            - Master Type: ${metadata.master_type}
                            - Worker Type: ${metadata.worker_type}
                            - Worker Count: ${metadata.worker_count}
                            """

                            // Convert to JSON safely
                            if (metadata && metadata instanceof Map) {
                                def safeMetadata = new HashMap(metadata)
                                env.CLUSTER_METADATA = groovy.json.JsonOutput.toJson(safeMetadata)
                            }
                        }
                    }
                }
            }
        }

        stage('Destroy Cluster') {
            when {
                expression { !params.DRY_RUN }
            }
            steps {
                withCredentials([
                    aws(
                        credentialsId: 'jenkins-openshift-aws',
                        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                    )
                ]) {
                    script {
                        def destroyConfig = [
                            clusterName: params.CLUSTER_NAME,
                            awsRegion: params.AWS_REGION,
                            s3Bucket: env.S3_BUCKET,
                            workDir: env.WORK_DIR,
                            cleanupRemaining: params.CLEANUP_REMAINING,
                            reason: params.DESTROY_REASON,
                            destroyedBy: env.BUILD_USER_ID ?: 'jenkins'
                        ]

                        // Destroy the cluster
                        def result = openshiftCluster.destroy(destroyConfig)

                        // Store result
                        env.DESTROY_RESULT = groovy.json.JsonOutput.toJson(result)

                        if (result.remainingResources && result.remainingResources.size() > 0) {
                            echo 'WARNING: Some resources may still exist after destruction'
                            unstable 'Cluster destroyed but some resources may remain'
                        }
                    }
                }
            }
        }

        stage('Dry Run Report') {
            when {
                expression { params.DRY_RUN }
            }
            steps {
                script {
                    echo """
                    ========================================
                    DRY RUN - Cluster Destruction Plan
                    ========================================
                    Cluster Name: ${params.CLUSTER_NAME}
                    AWS Region: ${params.AWS_REGION}
                    S3 Bucket: ${env.S3_BUCKET}

                    Actions that would be performed:
                    1. Download cluster state from S3
                    2. Run 'openshift-install destroy cluster'
                    3. Verify resource cleanup
                    ${params.CLEANUP_REMAINING ? '4. Attempt to clean up any remaining resources' : ''}
                    5. Delete cluster state from S3

                    Reason: ${params.DESTROY_REASON}
                    ========================================
                    """

                    currentBuild.description = "DRY RUN - ${params.CLUSTER_NAME}"
                }
            }
        }
    }

    post {
        success {
            script {
                if (!params.DRY_RUN) {
                    echo "Cluster ${params.CLUSTER_NAME} destroyed successfully"
                    currentBuild.description = "Destroyed: ${params.CLUSTER_NAME} - ${params.AWS_REGION}"

                    // Send notification if configured
                    if (env.SLACK_WEBHOOK) {
                        slackSend(
                            color: 'warning',
                            message: "OpenShift cluster destroyed: ${params.CLUSTER_NAME} in ${params.AWS_REGION} (Reason: ${params.DESTROY_REASON})"
                        )
                    }
                }
            }
        }
        failure {
            script {
                echo "Failed to destroy cluster ${params.CLUSTER_NAME}"
                currentBuild.description = "FAILED: ${params.CLUSTER_NAME}"

                // Send notification if configured
                if (env.SLACK_WEBHOOK) {
                    slackSend(
                        color: 'danger',
                        message: "Failed to destroy OpenShift cluster: ${params.CLUSTER_NAME}"
                    )
                }
            }
        }
        always {
            script {
                // Log destruction attempt
                if (!params.DRY_RUN) {
                    echo """
                    Destruction attempt summary:
                    - Cluster: ${params.CLUSTER_NAME}
                    - Region: ${params.AWS_REGION}
                    - Reason: ${params.DESTROY_REASON}
                    - User: ${env.BUILD_USER_ID ?: 'jenkins'}
                    - Build: ${BUILD_NUMBER}
                    - Time: ${new Date()}
                    """
                }
            }
        }
    }
}
