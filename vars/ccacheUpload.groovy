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
    def retentionDays = params.get('retentionDays', 60)
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
    def s3Path = "s3://${s3Bucket}/ccache/${project}/${mysqlVersion}/${cacheKey}/"
    
    echo "Uploading ccache to: ${s3Path}"
    echo "Cache key components:"
    echo "  Project: ${project}"
    echo "  MySQL Version: ${mysqlVersion}"
    echo "  Cache Key: ${cacheKey}"
    echo "  Retention Days: ${retentionDays}"
    echo "  Cloud Provider: ${cloudProvider}"
    
    // Show final ccache statistics
    sh '''
        if command -v ccache >/dev/null 2>&1; then
            echo "Final ccache statistics before upload:"
            ccache -s || true
        fi
    '''
    
    // Upload cache to S3 with lifecycle tag
    sh """
        set +e  # Don't fail if upload has issues
        
        # Tag for lifecycle management
        LIFECYCLE_TAG="retention=${retentionDays}days"
        
        if [ "${cloudProvider}" = "hetzner" ]; then
            # Use different S3 endpoint for Hetzner
            aws s3 sync \${CCACHE_DIR:-/tmp/ccache}/ ${s3Path} \
                --endpoint-url https://fsn1.your-objectstorage.com \
                --region ${s3Region} \
                --no-progress \
                --delete \
                --metadata "retention-days=${retentionDays}" || echo "Cache upload failed"
        else
            # Standard AWS S3
            aws s3 sync \${CCACHE_DIR:-/tmp/ccache}/ ${s3Path} \
                --region ${s3Region} \
                --no-progress \
                --delete \
                --metadata "retention-days=${retentionDays}" || echo "Cache upload failed"
        fi
        
        # List uploaded cache for verification
        echo "Uploaded cache contents:"
        if [ "${cloudProvider}" = "hetzner" ]; then
            aws s3 ls ${s3Path} \
                --endpoint-url https://fsn1.your-objectstorage.com \
                --region ${s3Region} || true
        else
            aws s3 ls ${s3Path} \
                --region ${s3Region} || true
        fi
        
        set -e
    """
    
    // List S3 cache buckets after upload for debugging if enabled
    def debugS3 = params.get('debugS3', env.CCACHE_DEBUG_S3 == 'true')
    
    if (debugS3) {
        echo "--- S3 cache status after upload ---"
        
        // Determine AWS CLI options based on cloud provider
        def awsOpts = ""
        if (cloudProvider == "hetzner") {
            awsOpts = "--endpoint-url https://fsn1.your-objectstorage.com --region ${s3Region}"
        } else {
            awsOpts = "--region ${s3Region}"
        }
        
        sh """
            set +e
            
            # List all cache keys for this version to verify upload
            CACHE_KEYS=\$(aws s3 ls s3://${s3Bucket}/ccache/${project}/${mysqlVersion}/ ${awsOpts} | grep PRE | awk '{print \$2}' | sed 's|/||' | tr '\n' ' ')
            echo "Cache keys after upload: \${CACHE_KEYS:-none}"
            
            # Show size of the uploaded cache
            CACHE_SIZE=\$(aws s3 ls ${s3Path} ${awsOpts} --recursive --summarize | grep "Total Size" | awk '{print \$3, \$4}')
            echo "Uploaded cache size: \${CACHE_SIZE:-error}"
            
            set -e
        """
    }
}