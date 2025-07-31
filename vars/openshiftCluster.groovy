// Main OpenShift cluster operations library
import groovy.json.JsonBuilder

/**
 * Creates a new OpenShift cluster on AWS with the specified configuration.
 *
 * @param config Map containing cluster configuration:
 *   - clusterName: Name for the cluster (required, lowercase alphanumeric with hyphens)
 *   - openshiftVersion: OpenShift version to install (required, e.g., '4.16.20' or 'latest')
 *   - awsRegion: AWS region (required, currently only 'us-east-2' supported)
 *   - pullSecret: Red Hat pull secret for OpenShift (required)
 *   - sshPublicKey: SSH public key for cluster access (required)
 *   - s3Bucket: S3 bucket for storing cluster state (required)
 *   - workDir: Working directory for cluster files (required)
 *   - baseDomain: Base domain for cluster URLs (optional, default: 'cd.percona.com')
 *   - masterType: EC2 instance type for masters (optional, default: 'm5.xlarge')
 *   - workerType: EC2 instance type for workers (optional, default: 'm5.large')
 *   - workerCount: Number of worker nodes (optional, default: 3)
 *   - deleteAfterHours: Auto-delete tag value (optional, default: '8')
 *   - deployPMM: Whether to deploy PMM (optional, default: true)
 *   - pmmVersion: PMM version to deploy (optional, default: '3.3.0')
 *   - pmmNamespace: Namespace for PMM deployment (optional, default: 'pmm-monitoring')
 *
 * @return Map containing cluster information (apiUrl, consoleUrl, kubeconfig, pmm details)
 */
def create(Map config) {
    def required = ['clusterName', 'openshiftVersion', 'awsRegion', 'pullSecret',
                    'sshPublicKey', 's3Bucket', 'workDir']

    required.each { param ->
        if (!config.containsKey(param) || !config[param]) {
            error "Missing required parameter: ${param}"
        }
    }

    def params = [
        baseDomain: 'cd.percona.com',
        masterType: 'm5.xlarge',
        workerType: 'm5.large',
        workerCount: 3,
        deleteAfterHours: '8',
        teamName: 'cloud',
        productTag: 'openshift',
        deployPMM: true,
        pmmVersion: '3.3.0',
        pmmNamespace: 'pmm-monitoring'
    ] + config

    openshiftTools.log('INFO', "Creating OpenShift cluster: ${params.clusterName}", params)

    try {
        // Step 1: Validate parameters
        openshiftTools.log('DEBUG', "Validating parameters for cluster ${params.clusterName}", params)
        validateParams(params)

        // Step 2: Ensure S3 bucket exists
        openshiftTools.log('DEBUG', "Ensuring S3 bucket exists: ${params.s3Bucket} in ${params.awsRegion}", params)
        openshiftS3.ensureS3BucketExists(params.s3Bucket, params.awsRegion, env.AWS_ACCESS_KEY_ID, env.AWS_SECRET_ACCESS_KEY)

        // Step 3: Install OpenShift tools
        openshiftTools.log('INFO', "Installing OpenShift tools for version: ${params.openshiftVersion}", params)
        def resolvedVersion = openshiftTools.install([
            openshiftVersion: params.openshiftVersion
        ])
        params.openshiftVersion = resolvedVersion
        openshiftTools.log('DEBUG', "Resolved OpenShift version: ${resolvedVersion}", params)

        // Step 4: Prepare cluster directory
        def clusterDir = "${params.workDir}/${params.clusterName}"
        openshiftTools.log('DEBUG', "Creating cluster directory: ${clusterDir}", params)
        sh "mkdir -p ${clusterDir}"

        // Step 5: Generate install config
        openshiftTools.log('DEBUG', 'Generating install-config.yaml', params)
        def installConfigData = generateInstallConfig(params)

        // Use writeYaml from Pipeline Utility Steps plugin to write YAML
        writeYaml file: "${clusterDir}/install-config.yaml", data: installConfigData
        // Also create a backup copy
        writeYaml file: "${clusterDir}/install-config.yaml.backup", data: installConfigData

        // Step 6: Create cluster metadata
        openshiftTools.log('DEBUG', 'Creating cluster metadata', params)
        def metadata = createMetadata(params, clusterDir)

        // Step 7: Create the cluster
        openshiftTools.log('INFO', 'Creating OpenShift cluster (this will take 30-45 minutes)...', params)

        // Print install-config.yaml content when debug mode is enabled
        if (env.OPENSHIFT_INSTALL_LOG_LEVEL == 'debug') {
            openshiftTools.log('DEBUG', 'install-config.yaml contents:', params)
            def yamlContent = readFile("${clusterDir}/install-config.yaml.backup")
            echo '====== install-config.yaml ======'
            echo yamlContent
            echo '================================='
        }

        sh """
            export PATH="\$HOME/.local/bin:\$PATH"
            cd ${clusterDir}
            openshift-install create cluster --log-level=info
        """

        // Step 8: Save cluster state to S3
        retry(3) {
            openshiftS3.uploadState([
                bucket: params.s3Bucket,
                clusterName: params.clusterName,
                region: params.awsRegion,
                workDir: params.workDir,
                metadata: metadata,
                accessKey: env.AWS_ACCESS_KEY_ID,
                secretKey: env.AWS_SECRET_ACCESS_KEY
            ])
        }

        // Step 9: Get cluster info
        def clusterInfo = getClusterInfo(clusterDir)

        // Validate critical files exist
        def criticalFiles = [
            "${clusterDir}/auth/kubeconfig",
            "${clusterDir}/auth/kubeadmin-password"
        ]
        criticalFiles.each { file ->
            if (!fileExists(file)) {
                error "Critical file missing after cluster creation: ${file}"
            }
        }

        // Create additional backup of auth directory in S3
        sh """
            cd ${clusterDir}
            tar -czf auth-backup.tar.gz auth/
            aws s3 cp auth-backup.tar.gz s3://${params.s3Bucket}/${params.clusterName}/auth-backup.tar.gz
            rm -f auth-backup.tar.gz
        """

        // Step 10: Deploy PMM if requested
        if (params.deployPMM) {
            env.KUBECONFIG = "${clusterDir}/auth/kubeconfig"
            def pmmInfo = deployPMM(params)

            metadata.pmmDeployed = true
            metadata.pmmVersion = params.pmmVersion
            metadata.pmmUrl = pmmInfo.url
            metadata.pmm_namespace = pmmInfo.namespace

            openshiftS3.saveMetadata([
                bucket: params.s3Bucket,
                clusterName: params.clusterName,
                region: params.awsRegion,
                accessKey: env.AWS_ACCESS_KEY_ID,
                secretKey: env.AWS_SECRET_ACCESS_KEY
            ], metadata)

            clusterInfo.pmm = pmmInfo
        }

        return clusterInfo
    } catch (Exception e) {
        openshiftTools.log('ERROR', "Failed to create OpenShift cluster: ${e.toString()}", params)
        error "Failed to create OpenShift cluster: ${e.toString()}"
    } finally {
        sh "rm -f ${params.workDir}/${params.clusterName}-state.tar.gz || true"
    }
}

/**
 * Destroys an existing OpenShift cluster and cleans up associated resources.
 *
 * @param config Map containing:
 *   - clusterName: Name of the cluster to destroy (required)
 *   - s3Bucket: S3 bucket containing cluster state (required)
 *   - awsRegion: AWS region where cluster exists (required)
 *   - workDir: Working directory (required)
 *
 * @return Map with destruction status
 */
def destroy(Map config) {
    def required = ['clusterName', 's3Bucket', 'awsRegion', 'workDir']
    required.each { param ->
        if (!config.containsKey(param) || !config[param]) {
            error "Missing required parameter: ${param}"
        }
    }

    def params = config

    openshiftTools.log('INFO', "Destroying OpenShift cluster: ${params.clusterName}", params)

    try {
        // Get metadata and cluster state from S3
        def metadataResult = openshiftS3.getMetadata([
            bucket: params.s3Bucket,
            clusterName: params.clusterName,
            region: params.awsRegion,
            accessKey: env.AWS_ACCESS_KEY_ID,
            secretKey: env.AWS_SECRET_ACCESS_KEY
        ])
        // Convert LazyMap to regular HashMap to avoid serialization issues
        def metadata = metadataResult ? new HashMap(metadataResult) : null

        if (!metadata) {
            error "No metadata found for cluster ${params.clusterName}."
        }

        def clusterDir = "${params.workDir}/${params.clusterName}"
        sh "mkdir -p ${clusterDir}"

        // Download cluster state from S3
        def stateExists = openshiftS3.downloadState([
            bucket: params.s3Bucket,
            clusterName: params.clusterName,
            region: params.awsRegion,
            workDir: params.workDir,
            accessKey: env.AWS_ACCESS_KEY_ID,
            secretKey: env.AWS_SECRET_ACCESS_KEY
        ])

        if (!stateExists) {
            error "No state found for cluster ${params.clusterName}."
        }

        // Install OpenShift tools
        if (metadata?.openshiftVersion) {
            openshiftTools.install([
                openshiftVersion: metadata.openshiftVersion
            ])
        }

        // Destroy the cluster
        if (stateExists) {
            openshiftTools.log('INFO', 'Destroying OpenShift cluster...', params)
            sh """
                export PATH="\$HOME/.local/bin:\$PATH"
                cd ${clusterDir}
                if [ -f auth/kubeconfig ]; then
                    export KUBECONFIG=\$(pwd)/auth/kubeconfig
                    echo "Using kubeconfig: \$KUBECONFIG"
                fi
                openshift-install destroy cluster --log-level=info || true
            """
        }

        // Clean up S3
        openshiftS3.cleanup([
            bucket: params.s3Bucket,
            clusterName: params.clusterName,
            region: params.awsRegion,
            accessKey: env.AWS_ACCESS_KEY_ID,
            secretKey: env.AWS_SECRET_ACCESS_KEY
        ])

        return [
            clusterName: params.clusterName,
            destroyed: true,
            s3Cleaned: true
        ]
    } catch (Exception e) {
        error "Failed to destroy OpenShift cluster: ${e.toString()}"
    } finally {
        sh "rm -rf ${params.workDir}/${params.clusterName} || true"
    }
}

/**
 * Lists all OpenShift clusters stored in S3 with their metadata.
 *
 * @param config Map containing:
 *   - region: AWS region to search (optional, default: 'us-east-2' or env.OPENSHIFT_AWS_REGION)
 *   - bucket: S3 bucket to search (optional, default: 'openshift-clusters-119175775298-us-east-2' or env.OPENSHIFT_S3_BUCKET)
 *
 * @return List of cluster information maps
 */
def list(Map config = [:]) {
    def params = [
        region: env.OPENSHIFT_AWS_REGION ?: 'us-east-2',
        bucket: env.OPENSHIFT_S3_BUCKET ?: 'openshift-clusters-119175775298-us-east-2'
    ] + config

    try {
        def clusters = []

        // Get cluster names using the S3 library
        // This replaces the previous AWS CLI approach for better error handling
        // Pass AWS credentials from environment (set by withCredentials)
        def clusterNames = openshiftS3.listClusters([
            bucket: params.bucket,
            region: params.region,
            accessKey: env.AWS_ACCESS_KEY_ID,
            secretKey: env.AWS_SECRET_ACCESS_KEY
        ])

        clusterNames.each { clusterName ->
            def metadata = openshiftS3.getMetadata([
                bucket: params.bucket,
                clusterName: clusterName,
                region: params.region,
                accessKey: env.AWS_ACCESS_KEY_ID,
                secretKey: env.AWS_SECRET_ACCESS_KEY
            ])

            if (metadata) {
                clusters << [
                    name: clusterName,
                    version: metadata.openshift_version ?: 'Unknown',
                    region: metadata.aws_region ?: params.region,
                    created_by: metadata.created_by ?: 'Unknown',
                    created_at: metadata.created_date ?: 'Unknown',
                    pmm_deployed: metadata.pmm_deployed ? 'Yes' : 'No',
                    pmm_version: metadata.pmm_version ?: 'N/A'
                ]
            }
        }

        return clusters
    } catch (Exception e) {
        error "Failed to list OpenShift clusters: ${e.message}"
    }
}

/**
 * Validates cluster creation parameters to ensure they meet requirements.
 * Throws error if validation fails.
 *
 * @param params Map of parameters to validate
 */
def validateParams(Map params) {
    // Validate cluster name format:
    // - Must start with a lowercase letter
    // - Can contain lowercase letters and numbers
    // - Can contain hyphens, but not consecutive or at start/end
    // - Maximum 20 characters
    if (!params.clusterName.matches(/^[a-z][a-z0-9]*(-[a-z0-9]+)*$/)) {
        error "Invalid cluster name: '${params.clusterName}'. Must start with lowercase letter, contain only lowercase letters, numbers, and non-consecutive hyphens."
    }

    if (params.clusterName.length() > 20) {
        error 'Cluster name too long. Maximum 20 characters.'
    }

    // Validate OpenShift version format
    if (!params.openshiftVersion.matches(/^(latest|stable|fast|candidate|eus-[0-9]+\.[0-9]+|latest-[0-9]+\.[0-9]+|stable-[0-9]+\.[0-9]+|fast-[0-9]+\.[0-9]+|candidate-[0-9]+\.[0-9]+|[0-9]+\.[0-9]+(\.[0-9]+)?)$/)) {
        error "Invalid OpenShift version: '${params.openshiftVersion}'. Use specific version (4.16.20), channel (latest), or channel-version (stable-4.16)."
    }

    // Region restriction due to OpenShift installer AMI availability and DNS zones
    if (params.awsRegion != 'us-east-2') {
        error "Unsupported AWS region: '${params.awsRegion}'. Currently only 'us-east-2' is supported."
    }
}

/**
 * Generates OpenShift install-config.yaml based on provided parameters.
 *
 * @param params Map containing cluster configuration
 * @return String YAML content for install-config.yaml
 */
def generateInstallConfig(Map params) {
    def config = [
        apiVersion: 'v1',
        baseDomain: params.baseDomain,
        compute: [[
            architecture: 'amd64',
            hyperthreading: 'Enabled',
            name: 'worker',
            platform: [
                aws: [
                    type: params.workerType
                ]
            ],
            replicas: params.workerCount
        ]],
        controlPlane: [
            architecture: 'amd64',
            hyperthreading: 'Enabled',
            name: 'master',
            platform: [
                aws: [
                    type: params.masterType
                ]
            ],
            replicas: 3
        ],
        metadata: [
            name: params.clusterName
        ],
        networking: [
            clusterNetwork: [[
                cidr: '10.128.0.0/14',
                hostPrefix: 23
            ]],
            machineNetwork: [[
                cidr: '10.0.0.0/16'
            ]],
            networkType: 'OVNKubernetes',
            serviceNetwork: ['172.30.0.0/16']
        ],
        platform: [
            aws: [
                region: params.awsRegion,
                // AWS tags are crucial for cost tracking and automated cleanup
                userTags: [
                    'iit-billing-tag': 'openshift',  // Required for internal billing
                    'delete-cluster-after-hours': params.deleteAfterHours,  // Used by cleanup automation
                    'team': params.teamName,
                    'product': params.productTag,
                    'owner': params.buildUser ?: env.BUILD_USER_ID ?: 'jenkins',
                    'creation-time': (System.currentTimeMillis() / 1000).longValue().toString()  // Unix timestamp in seconds
                ]
            ]
        ],
        pullSecret: params.pullSecret,
        sshKey: params.sshPublicKey
    ]

    // Convert to YAML format as required by OpenShift installer
    // We'll write the YAML directly in the calling method using writeYaml step
    return config
}

/**
 * Creates metadata JSON file with cluster information for tracking.
 *
 * @param params Map containing cluster configuration
 * @param clusterDir Directory where metadata will be saved
 * @return Map of metadata that was saved
 */
def createMetadata(Map params, String clusterDir) {
    def metadata = [
        cluster_name: params.clusterName,
        openshift_version: params.openshiftVersion,
        aws_region: params.awsRegion,
        created_date: new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'"),
        created_by: params.buildUser ?: env.BUILD_USER_ID ?: 'jenkins',
        jenkins_build: env.BUILD_NUMBER ?: '1',
        master_type: params.masterType,
        worker_type: params.workerType,
        worker_count: params.workerCount
    ]

    def json = new JsonBuilder(metadata).toPrettyString()
    writeFile file: "${clusterDir}/metadata.json", text: json

    return metadata
}

/**
 * Deploys Percona Monitoring and Management (PMM) to the OpenShift cluster.
 * Creates namespace, sets permissions, and deploys via Helm.
 *
 * @param params Map containing:
 *   - pmmVersion: Version to deploy
 *   - pmmNamespace: Namespace for PMM deployment (defaults to 'pmm-monitoring')
 * @return Map with PMM access details (url, username, password, namespace)
 */
def deployPMM(Map params) {
    openshiftTools.log('INFO', "Deploying PMM ${params.pmmVersion} to namespace ${params.pmmNamespace}...", params)

    sh """
        export PATH="\$HOME/.local/bin:\$PATH"
        # Create namespace
        oc create namespace ${params.pmmNamespace} || true

        # Grant anyuid SCC permissions
        # PMM requires elevated privileges to run monitoring components
        oc adm policy add-scc-to-user anyuid -z default -n ${params.pmmNamespace}
        oc adm policy add-scc-to-user anyuid -z pmm -n ${params.pmmNamespace}

        # Add Percona Helm repo
        helm repo add percona https://percona.github.io/percona-helm-charts/ || true
        helm repo update

        # Deploy PMM using Helm (will be retried if it fails)
        echo "Deploying PMM with Helm..."
    """

    sh """
        export PATH="\$HOME/.local/bin:\$PATH"
        helm upgrade --install pmm percona/pmm \
            --namespace ${params.pmmNamespace} \
            --version ${params.pmmVersion.startsWith('3.') ? '1.4.6' : '1.3.12'} \
            --set platform=openshift \
            --set service.type=ClusterIP \
            --set pmmAdminPassword='admin' \
            --wait --timeout 10m
    """

    sh """
        export PATH="\$HOME/.local/bin:\$PATH"
        # Create OpenShift route for HTTPS access
        oc create route edge pmm-https \
            --service=pmm \
            --port=https \
            --insecure-policy=Redirect \
            -n ${params.pmmNamespace} || true
    """

    def pmmUrl = sh(
        script: """
            export PATH="\$HOME/.local/bin:\$PATH"
            oc get route pmm-https -n ${params.pmmNamespace} -o jsonpath='{.spec.host}'
        """,
        returnStdout: true
    ).trim()

    return [
        url: "https://${pmmUrl}",
        username: 'admin',
        password: 'admin',
        namespace: params.pmmNamespace
    ]
}

/**
 * Extracts cluster access information from OpenShift installation files.
 *
 * @param clusterDir Directory containing OpenShift installation files
 * @return Map with cluster access details (apiUrl, consoleUrl, kubeadminPassword, kubeconfig)
 */
def getClusterInfo(String clusterDir) {
    def info = [:]

    info.apiUrl = sh(
        script: "grep 'server:' ${clusterDir}/auth/kubeconfig | head -1 | awk '{print \$2}'",
        returnStdout: true
    ).trim()

    def consoleRoute = sh(
        script: """
            export PATH="\$HOME/.local/bin:\$PATH"
            export KUBECONFIG=${clusterDir}/auth/kubeconfig
            oc get route -n openshift-console console -o jsonpath='{.spec.host}' 2>/dev/null || echo ''
        """,
        returnStdout: true
    ).trim()

    if (consoleRoute) {
        info.consoleUrl = "https://${consoleRoute}"
    }

    def kubeadminFile = "${clusterDir}/auth/kubeadmin-password"
    if (fileExists(kubeadminFile)) {
        info.kubeadminPassword = readFile(kubeadminFile).trim()
    }

    info.kubeconfig = "${clusterDir}/auth/kubeconfig"
    info.clusterDir = clusterDir

    return info
}

/**
 * Backward compatibility method - delegates to create().
 * Allows calling the library directly without method name.
 *
 * @param config Cluster configuration map
 * @return Result from create() method
 */
def call(Map config) {
    return create(config)
}
