// groovylint-disable-next-line UnusedVariable, VariableName
@Library('jenkins-pipelines') _

pipeline {
    agent {
        label 'agent-amd64-ol9'
    }

    environment {
        OPENSHIFT_AWS_REGION = "${params.AWS_REGION ?: 'us-east-2'}"
    }

    options {
        skipDefaultCheckout()
        buildDiscarder(logRotator(numToKeepStr: '50', daysToKeepStr: '7'))
        timestamps()
    }

    stages {
        stage('List OpenShift Clusters') {
            steps {
                withCredentials([
                    aws(
                        credentialsId: 'jenkins-openshift-aws',
                        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                    )
                ]) {
                    script {
                        def clusters = openshiftCluster.list([
                            region: env.OPENSHIFT_AWS_REGION,
                            format: params.OUTPUT_FORMAT ?: 'table',
                            accessKey: AWS_ACCESS_KEY_ID,
                            secretKey: AWS_SECRET_ACCESS_KEY
                        ])

                        if (clusters.isEmpty()) {
                            echo "No OpenShift clusters found in region ${env.OPENSHIFT_AWS_REGION}"
                        } else {
                            echo "Found ${clusters.size()} OpenShift cluster(s):"
                            echo ''

                            // Print table header
                            echo 'CLUSTER NAME                 VERSION REGION      CREATED AT           PMM DEPLOYED  PMM VERSION'
                            echo '---------------------------- ------- ----------- -------------------- ------------- -------------'

                            clusters.each { cluster ->
                                def name = cluster.name.padRight(28)
                                def version = cluster.version.padRight(7)
                                def region = cluster.region.padRight(11)
                                def createdAt = cluster.created_at.padRight(20)
                                def pmmDeployed = cluster.pmm_deployed.padRight(13)
                                def pmmVersion = cluster.pmm_version.padRight(13)

                                echo "${name} ${version} ${region} ${createdAt} ${pmmDeployed} ${pmmVersion}"
                            }
                        }
                    }
                }
            }
        }
    }

    post {
        always {
            cleanWs()
        }
    }
}
