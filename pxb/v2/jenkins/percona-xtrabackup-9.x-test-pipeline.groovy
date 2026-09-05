// Worker labels: x86_64 on Hetzner = docker-x64; aarch64 on Hetzner = docker-aarch64.
// AWS uses docker-32gb / docker-32gb-aarch64. MICRO_LABEL (small orchestrator
// worker) is always x86_64; it just drives the build, the actual docker run
// happens on LABEL.
if (!(params.ARCH in ['x86_64', 'aarch64'])) {
    error("Unsupported ARCH '${params.ARCH}'; expected x86_64 or aarch64")
}

String LABEL
if (params.CLOUD == 'AWS') {
    LABEL = (params.ARCH == 'aarch64') ? 'docker-32gb-aarch64' : 'docker-32gb'
} else {
    LABEL = (params.ARCH == 'aarch64') ? 'docker-aarch64' : 'docker-x64'
}
String MICRO_LABEL = (params.CLOUD == 'AWS') ? 'micro-amazon' : 'launcher-x64'

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
            choices: 'oraclelinux:9\nubuntu:jammy\nubuntu:noble\ndebian:bookworm\ndebian:trixie\namazonlinux:2023\nasan',
            description: 'OS version for compilation',
            name: 'DOCKER_OS')
        choice(
            choices: 'x86_64\naarch64',
            description: 'CPU architecture; selects the pxc-build image variant and the worker label.',
            name: 'ARCH')
        choice(
            choices: 'RelWithDebInfo\nDebug',
            description: 'Type of build to produce',
            name: 'CMAKE_BUILD_TYPE')
        choice(
            choices: 'innodb9x\nxtradb9x',
            description: 'MySQL server flavour for QA run',
            name: 'XTRABACKUP_TARGET')
        string(
            defaultValue: '9.6.0',
            description: 'Version of MySQL InnoDB which will be used for bootstrap.sh script',
            name: 'INNODB9X_VERSION')
        string(
            defaultValue: '9.6.0-1',
            description: 'Version of Percona XtraDB which will be used for bootstrap.sh script',
            name: 'XTRADB9X_VERSION')
        string(
            defaultValue: '',
            description: './run.sh options, for options like: -j N Run tests in N parallel processes, -T seconds, -x options  Extra options to pass to xtrabackup',
            name: 'XBTR_ARGS')
        string(
            defaultValue: '',
            description: 'Pass an URL for downloading bootstrap.sh, If empty will use from repository you specified',
            name: 'BOOTSTRAP_URL')
        booleanParam(
            defaultValue: false,
            description: 'Starts Microsoft Azurite emulator and tests xbcloud against it',
            name: 'WITH_AZURITE')
        booleanParam(
            name: 'WITH_XBCLOUD_TESTS',
            defaultValue: true,
            description: 'Run xbcloud tests')
        booleanParam(
            name: 'WITH_VAULT_TESTS',
            defaultValue: true,
            description: 'Run vault tests')
        booleanParam(
            name: 'WITH_KMIP_TESTS',
            defaultValue: true,
            description: 'Run kmip tests')
        choice(
            choices: 'Hetzner\nAWS',
            description: 'Host provider for Jenkins workers',
            name: 'CLOUD')
    }
    agent {
        label MICRO_LABEL
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
                    sh '''#!/bin/bash
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
                                    docker rm --force azurite || :
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
            agent { label MICRO_LABEL }
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
