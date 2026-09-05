if (params.ARCH != 'x86_64') {
    error("Unsupported ARCH '${params.ARCH}'; PXB 2.4 requires x86_64")
}

pipeline_timeout = 10

pipeline {
    parameters {
        string(
            defaultValue: '',
            description: 'Compile producer in this folder. Empty selects the sibling compile pipeline.',
            name: 'COMPILE_JOB')
        string(
            defaultValue: 'lastSuccessfulBuild',
            description: 'Exact successful compile build number, or lastSuccessfulBuild for a standalone run.',
            name: 'USE_BINARIES_FROM_BUILD_ID')
        choice(
            choices: 'centos:7\ncentos:8\nubuntu:xenial\nubuntu:bionic\nubuntu:focal\ndebian:stretch\ndebian:buster\ndebian:bullseye\nasan',
            description: 'OS version for compilation',
            name: 'DOCKER_OS')
        choice(
            choices: 'x86_64',
            description: 'CPU architecture. PXB 2.4 is EOL and builds x86_64 only (no aarch64 base images for centos:7/EOL distros).',
            name: 'ARCH')
        choice(
            choices: 'RelWithDebInfo\nDebug',
            description: 'Type of build to produce',
            name: 'CMAKE_BUILD_TYPE')
        choice(
            choices: 'innodb56\ninnodb57\nxtradb56\nxtradb57\ngalera56\ngalera57',
            description: 'MySQL version for QA run',
            name: 'XTRABACKUP_TARGET')
        string(
            defaultValue: '5.6.49',
            description: 'Version of MySQL InnoDB56 which will be used for bootstrap.sh script',
            name: 'INNODB56_VERSION')
        string(
            defaultValue: '5.7.31',
            description: 'Version of MySQL InnoDB57 which will be used for bootstrap.sh script',
            name: 'INNODB57_VERSION')
        string(
            defaultValue: '5.6.49-89.0',
            description: 'Version of Percona XtraDB56 which will be used for bootstrap.sh script',
            name: 'XTRADB56_VERSION')
        string(
            defaultValue: '5.7.31-34',
            description: 'Version of Percona XtraDB57 which will be used for bootstrap.sh script',
            name: 'XTRADB57_VERSION')
        string(
            defaultValue: '',
            description: './run.sh options, for options like: -j N Run tests in N parallel processes, -T seconds, -x options  Extra options to pass to xtrabackup',
            name: 'XBTR_ARGS')
        string(
            defaultValue: '',
            description: 'Pass an URL for downloading bootstrap.sh, If empty will use from repository you specified in PXB24_REPO',
            name: 'BOOTSTRAP_URL')
        choice(
            choices: 'docker',
            description: 'Run build on specified instance type',
            name: 'LABEL')
    }
    agent {
        label 'micro-amazon'
    }
    options {
        skipDefaultCheckout()
        skipStagesAfterUnstable()
        timeout(time: 10, unit: 'HOURS')
        buildDiscarder(logRotator(numToKeepStr: '200', artifactNumToKeepStr: '200'))
    }
    stages {
        stage('Test') {
            agent { label LABEL }
            steps {
                timeout(time: 240, unit: 'MINUTES')  {
                    script {
                        currentBuild.displayName = "${BUILD_NUMBER} ${CMAKE_BUILD_TYPE}/${DOCKER_OS}"
                    }
                    sh 'echo Prepare: \$(date -u "+%s")'
                    script {
                        def checkedOut = checkout scm
                        String revision = checkedOut?.GIT_COMMIT ?: ''
                        if (!(revision ==~ /[a-f0-9]{40}/)) {
                            error('Checkout did not return a full pipeline commit SHA')
                        }
                        env.PXB_PIPELINE_REVISION = revision
                    }
                    sh 'python3 pxb/v2/ci/verify_worker.py'
                    sh '''
                        # sudo is needed for better node recovery after compilation failure
                        # if building failed on compilation stage directory will have files owned by docker user
                        sudo git reset --hard
                        sudo git clean -xdf
                        cd pxb/v2
                        rm -rf sources/results
                        sudo git -C sources reset --hard || :
                        sudo git -C sources clean -xdf   || :
                        '''
                    script {
                        load('pxb/v2/ci/copyCompileInput.groovy').call()
                    }
                    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: '24e68886-c552-4033-8503-ed85bbaa31f3', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                        timeout(time: 10, unit: 'MINUTES') {
                            sh '''#!/bin/bash
                                set -euo pipefail
                                python3 pxb/v2/ci/fetch_compile_artifact.py \\
                                    --input compile-input \\
                                    --output pxb/v2/sources/results/binary.tar.gz \\
                                    --provenance pxb/v2/sources/results/compile-input.json
                            '''
                        }
                        archiveArtifacts artifacts: 'pxb/v2/sources/results/compile-input.json', followSymlinks: false, fingerprint: true
                        sh '''#!/bin/bash
                            set -euo pipefail
                            export AWS_MAX_ATTEMPTS=3 AWS_RETRY_MODE=standard
                            cd pxb/v2
                            aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0
                            echo Test: \$(date -u "+%s")
                            sg docker -c "
                                if [ \$(docker ps -q | wc -l) -ne 0 ]; then
                                    docker ps -q | xargs docker stop --time 1 || :
                                fi
                                ulimit -a
                                ./docker/run-test ${DOCKER_OS} ${ARCH}
                            "
                            echo Archive test: \$(date -u "+%s")
                            gzip sources/results/* || true
                            if [[ -d sources/results/results/ ]]; then
                                tar -zcvf results.tar.gz sources/results/results/
                                mv results.tar.gz sources/results/
                            fi
                            aws s3 sync --no-progress --acl public-read --exclude 'binary.tar.gz' \\
                                --cli-connect-timeout 10 --cli-read-timeout 30 \\
                                ./sources/results/ s3://pxb-build-cache/${BUILD_TAG}/
                        '''
                    }
                }
            }
        }
        stage('Archive Test Results') {
            agent { label 'micro-amazon' }
            steps {
                retry(3) {
                deleteDir()
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: '24e68886-c552-4033-8503-ed85bbaa31f3', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        aws s3 cp --no-progress s3://pxb-build-cache/${BUILD_TAG}/xbtr.output.gz ./ || true
                        aws s3 cp --no-progress s3://pxb-build-cache/${BUILD_TAG}/junit.xml.gz ./ || true
                        aws s3 cp --no-progress s3://pxb-build-cache/${BUILD_TAG}/test_results.subunit.gz ./ || true
                        aws s3 cp --no-progress s3://pxb-build-cache/${BUILD_TAG}/results.tar.gz ./ || true
                        gunzip < xbtr.output.gz > xbtr.output || true
                        gunzip < junit.xml.gz > junit.xml || true
                        gunzip < test_results.subunit.gz > test_results.subunit || true
                    '''
                }
                archiveArtifacts allowEmptyArchive: true, followSymlinks: false, onlyIfSuccessful: true, artifacts: 'xbtr.output,junit.xml,test_results.subunit,results.tar.gz'
                step([$class: 'JUnitResultArchiver', testResults: 'junit.xml', healthScaleFactor: 1.0])
                }
            }
        }
    }
    post {
        always {
            sh '''
                echo Finish: \$(date -u "+%s")
            '''
        }
    }
}
