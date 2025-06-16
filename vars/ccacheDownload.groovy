def call(Map params = [:]) {
    // Required parameters
    def requiredParams = ['project', 'mysqlVersion', 'cacheKey']
    requiredParams.each { param ->
        if (!params.containsKey(param) || !params[param]) {
            error "Missing required parameter: ${param}"
        }
    }
    
    def project = params.project
    def mysqlVersion = params.mysqlVersion
    def cacheKey = params.cacheKey
    def forceCache = params.get('forceCacheMiss', false)
    def cloudProvider = params.get('cloudProvider', env.S3_CLOUD_PROVIDER ?: 'aws')
    
    // S3 bucket configuration based on cloud provider
    def s3Bucket = ''
    def s3Region = ''
    
    switch(cloudProvider) {
        case 'hetzner':
            s3Bucket = 'ps-build-cache'
            s3Region = 'fsn1'
            break
        case 'aws':
        default:
            s3Bucket = 'ps-build-cache'
            s3Region = 'us-east-1'
    }
    
    // Construct S3 path
    def s3Prefix = forceCache ? "force-cache-miss" : "ccache"
    def s3Path = "s3://${s3Bucket}/${s3Prefix}/${project}/${mysqlVersion}/${cacheKey}/"
    
    echo "Downloading ccache from: ${s3Path}"
    echo "Cache key components:"
    echo "  Project: ${project}"
    echo "  MySQL Version: ${mysqlVersion}"
    echo "  Cache Key: ${cacheKey}"
    echo "  Cloud Provider: ${cloudProvider}"
    
    // Ensure ccache directory exists with proper permissions
    sh '''
        mkdir -p ${CCACHE_DIR:-/tmp/ccache}
        chmod 777 ${CCACHE_DIR:-/tmp/ccache}
    '''
    
    // Download cache from S3
    sh """
        set +e  # Don't fail if cache doesn't exist
        
        if [ "${cloudProvider}" = "hetzner" ]; then
            # Use different S3 endpoint for Hetzner
            aws s3 sync ${s3Path} \${CCACHE_DIR:-/tmp/ccache}/ \
                --endpoint-url https://fsn1.your-objectstorage.com \
                --region ${s3Region} \
                --no-progress || echo "No cache found or download failed"
        else
            # Standard AWS S3
            aws s3 sync ${s3Path} \${CCACHE_DIR:-/tmp/ccache}/ \
                --region ${s3Region} \
                --no-progress || echo "No cache found or download failed"
        fi
        
        # List cache contents for debugging
        echo "Cache directory contents:"
        ls -la \${CCACHE_DIR:-/tmp/ccache}/ || true
        
        # Show ccache statistics if available
        if command -v ccache >/dev/null 2>&1; then
            echo "Initial ccache statistics:"
            ccache -s || true
        fi
        
        set -e
    """
    
    // List S3 cache buckets for debugging if enabled (requested by Przemyslaw in PR review)
    def debugS3 = params.get('debugS3', env.CCACHE_DEBUG_S3 == 'true')
    
    if (debugS3) {
        echo "--- S3 cache listing ---"
        
        // Determine AWS CLI options based on cloud provider
        def awsOpts = ""
        if (cloudProvider == "hetzner") {
            awsOpts = "--endpoint-url https://fsn1.your-objectstorage.com --region ${s3Region}"
        } else {
            awsOpts = "--region ${s3Region}"
        }
        
        sh """
            set +e
            
            # List all available versions for this project
            VERSIONS=\$(aws s3 ls s3://${s3Bucket}/${s3Prefix}/${project}/ ${awsOpts} | grep PRE | awk '{print \$2}' | sed 's|/||' | tr '\n' ' ')
            echo "Available versions: \${VERSIONS:-none}"
            
            # List all cache keys for the current version
            CACHE_KEYS=\$(aws s3 ls s3://${s3Bucket}/${s3Prefix}/${project}/${mysqlVersion}/ ${awsOpts} | grep PRE | awk '{print \$2}' | sed 's|/||' | tr '\n' ' ')
            echo "Cache keys for ${mysqlVersion}: \${CACHE_KEYS:-none}"
            
            # Show size of the current cache
            CACHE_SIZE=\$(aws s3 ls ${s3Path} ${awsOpts} --recursive --summarize | grep "Total Size" | awk '{print \$3, \$4}')
            echo "Current cache size: \${CACHE_SIZE:-not found}"
            
            set -e
        """
    }
}