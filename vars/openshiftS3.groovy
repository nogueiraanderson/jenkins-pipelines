// OpenShift S3 state management library
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.amazonaws.services.s3.model.*
import com.amazonaws.AmazonServiceException
import com.amazonaws.services.s3.model.AmazonS3Exception
import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.auth.BasicAWSCredentials
import java.io.File

/**
 * Helper method to build S3 client with credentials from environment if available.
 * This ensures we use credentials from withCredentials block instead of EC2 instance profile.
 *
 * @param region AWS region for the S3 client
 * @param accessKey Optional AWS access key (if null, checks env)
 * @param secretKey Optional AWS secret key (if null, checks env)
 * @return Configured S3 client
 */
@NonCPS
def buildS3Client(String region, String accessKey = null, String secretKey = null) {
    // First try passed credentials, then environment variables
    def awsAccessKey = accessKey ?: System.getenv('AWS_ACCESS_KEY_ID')
    def awsSecretKey = secretKey ?: System.getenv('AWS_SECRET_ACCESS_KEY')

    if (awsAccessKey && awsSecretKey) {
        def awsCreds = new BasicAWSCredentials(awsAccessKey, awsSecretKey)
        return AmazonS3ClientBuilder.standard()
            .withRegion(region)
            .withCredentials(new AWSStaticCredentialsProvider(awsCreds))
            .build()
    } else {
        return AmazonS3ClientBuilder.standard()
            .withRegion(region)
            .build()
    }
}

/**
 * Uploads OpenShift cluster state to S3 for backup and recovery.
 * Creates a tarball of the cluster directory and uploads with metadata.
 *
 * @param config Map containing:
 *   - bucket: S3 bucket name (required)
 *   - clusterName: Name of the cluster (required)
 *   - region: AWS region (required)
 *   - workDir: Working directory containing cluster files (required)
 *   - metadata: Optional metadata map to store alongside state
 * @return String S3 URI of uploaded state file
 */
def uploadState(Map config) {
    def required = ['bucket', 'clusterName', 'region', 'workDir']
    required.each { param ->
        if (!config.containsKey(param) || !config[param]) {
            error "Missing required parameter: ${param}"
        }
    }

    openshiftTools.log('INFO', 'Backing up cluster state to S3...', config)

    // Create tarball of cluster state
    // Use list form to avoid shell injection
    sh(script: "cd '${config.workDir}' && tar -czf 'cluster-state.tar.gz' '${config.clusterName}/'")

    def s3Key = "${config.clusterName}/cluster-state.tar.gz"
    def localPath = "${config.workDir}/cluster-state.tar.gz"

    // Perform the S3 upload in a separate method to avoid serialization issues
    def result = performS3Upload(
        config.bucket,
        s3Key,
        localPath,
        config.region,
        config.metadata,
        config.accessKey,
        config.secretKey
    )

    if (result.error) {
        error "Failed to upload state to S3: ${result.error}"
    }

    // Also save metadata JSON if provided
    if (config.metadata) {
        saveMetadata([
            bucket: config.bucket,
            clusterName: config.clusterName,
            region: config.region,
            accessKey: config.accessKey,
            secretKey: config.secretKey
        ], config.metadata)
    }

    // Clean up local tarball file
    sh(script: "rm -f '${localPath}' || true")

    return result.s3Uri
}

/**
 * Downloads OpenShift cluster state from S3 and extracts it.
 * Used for cluster recovery or destruction operations.
 *
 * @param config Map containing:
 *   - bucket: S3 bucket name (required)
 *   - clusterName: Name of the cluster (required)
 *   - region: AWS region (required)
 *   - workDir: Directory where state will be extracted (required)
 * @return boolean true if state was found and downloaded, false if not found
 */
def downloadState(Map config) {
    def required = ['bucket', 'clusterName', 'region', 'workDir']
    required.each { param ->
        if (!config.containsKey(param) || !config[param]) {
            error "Missing required parameter: ${param}"
        }
    }

    def s3Key = "${config.clusterName}/cluster-state.tar.gz"
    def localPath = "${config.workDir}/cluster-state.tar.gz"

    openshiftTools.log('INFO', 'Downloading cluster state from S3...', config)

    // Perform the S3 download in a separate method to avoid serialization issues
    def downloaded = performS3Download(config.bucket, s3Key, localPath, config.region, config.accessKey, config.secretKey)

    if (!downloaded) {
        openshiftTools.log('WARN', "No state found in S3 for cluster: ${config.clusterName}", config)
        return false
    }

    // Extract state
    // Use proper escaping to avoid shell injection
    sh(script: "cd '${config.workDir}' && tar -xzf 'cluster-state.tar.gz' && rm -f 'cluster-state.tar.gz'")

    return true
}

/**
 * Performs the actual S3 download operation.
 * Separated to handle non-serializable S3 client objects.
 */
@NonCPS
private performS3Download(String bucket, String s3Key, String localPath, String region, String accessKey = null, String secretKey = null) {
    def s3Client = buildS3Client(region, accessKey, secretKey)

    try {
        // Check if state exists
        try {
            s3Client.getObjectMetadata(bucket, s3Key)
        } catch (AmazonServiceException e) {
            if (e.getStatusCode() == 404) {
                return false
            }
            throw e
        }

        // Download state
        def getRequest = new GetObjectRequest(bucket, s3Key)
        def file = new File(localPath)
        s3Client.getObject(getRequest, file)

        return true
    } finally {
        s3Client.shutdown()
    }
}

/**
 * Saves cluster metadata as JSON to S3.
 * Separate from state files for quick access without downloading full state.
 *
 * @param params Map containing bucket, clusterName, and region
 * @param metadata Map of metadata to save
 */
def saveMetadata(Map params, Map metadata) {
    def s3Key = "${params.clusterName}/metadata.json"
    def json = new JsonBuilder(metadata).toPrettyString()

    // Perform the S3 metadata save in a separate method to avoid serialization issues
    def result = performSaveMetadata(
        params.bucket,
        s3Key,
        json,
        params.region,
        params.accessKey,
        params.secretKey
    )

    if (result.error) {
        openshiftTools.log('ERROR', "Failed to save metadata: ${result.error}", params)
        error "Failed to save metadata to S3: ${result.error}"
    }
}

/**
 * Retrieves cluster metadata JSON from S3.
 *
 * @param params Map containing bucket, clusterName, region, and optional accessKey/secretKey
 * @return Map parsed metadata or null if not found
 */
def getMetadata(Map params) {
    // Use a separate NonCPS method to perform S3 operations
    def result = performGetMetadata(params.bucket, params.clusterName, params.region, params.accessKey, params.secretKey)

    if (result.error) {
        openshiftTools.log('ERROR', "Failed to get metadata: ${result.error}", params)
        return null
    }

    if (result.notFound) {
        openshiftTools.log('WARN', "No metadata found for cluster: ${params.clusterName}", params)
        return null
    }

    if (result.fromObjectMetadata) {
        openshiftTools.log('INFO', "Found metadata in S3 object metadata for cluster: ${params.clusterName}", params)
    }

    return result.metadata
}

/**
 * Performs the actual S3 metadata retrieval.
 * Separated to handle non-serializable S3 client objects.
 */
@NonCPS
private performGetMetadata(String bucket, String clusterName, String region, String accessKey = null, String secretKey = null) {
    def s3Key = "${clusterName}/metadata.json"
    def s3Client = buildS3Client(region, accessKey, secretKey)
    def s3Object = null

    try {
        // First try to get metadata.json
        try {
            s3Object = s3Client.getObject(bucket, s3Key)
            def json = s3Object.getObjectContent().text

            if (json) {
                def lazyMap = new JsonSlurper().parseText(json)
                // Convert LazyMap to regular HashMap to avoid serialization issues
                def metadata = new HashMap(lazyMap)
                return [metadata: metadata, error: null, notFound: false, fromObjectMetadata: false]
            }
        } catch (AmazonServiceException e) {
            if (e.getStatusCode() == 404) {
                // Try to get metadata from cluster-state.tar.gz object metadata
                try {
                    def stateKey = "${clusterName}/cluster-state.tar.gz"
                    def headResult = s3Client.getObjectMetadata(bucket, stateKey)
                    def userMetadata = headResult.getUserMetadata()

                    if (userMetadata && !userMetadata.isEmpty()) {
                        // Convert S3 user metadata to regular metadata map
                        // S3 returns user metadata keys with hyphens, but we need underscores for consistency
                        def metadata = [:]
                        userMetadata.each { key, value ->
                            // Convert hyphens to underscores to match expected format
                            def normalizedKey = key.replaceAll('-', '_')
                            metadata[normalizedKey] = value
                        }

                        return [metadata: metadata, error: null, notFound: false, fromObjectMetadata: true]
                    }
                } catch (AmazonServiceException e2) {
                    if (e2.getStatusCode() != 404) {
                        return [metadata: null, error: e2.message, notFound: false, fromObjectMetadata: false]
                    }
                }

                return [metadata: null, error: null, notFound: true, fromObjectMetadata: false]
            }
            throw e
        }
    } catch (Exception e) {
        return [metadata: null, error: e.message, notFound: false, fromObjectMetadata: false]
    } finally {
        if (s3Object != null) {
            s3Object.close()
        }
        s3Client.shutdown()
    }
}

/**
 * Performs the actual S3 upload operation.
 * Separated to handle non-serializable S3 client objects.
 */
@NonCPS
private performS3Upload(String bucket, String s3Key, String localPath, String region, Map metadata, String accessKey = null, String secretKey = null) {
    def s3Client = buildS3Client(region, accessKey, secretKey)

    try {
        // Prepare metadata
        def objectMetadata = new ObjectMetadata()
        if (metadata && metadata instanceof Map) {
            metadata.each { k, v ->
                objectMetadata.addUserMetadata(k.toString(), v.toString())
            }
        }

        // Upload file to S3
        def file = new File(localPath)
        def putRequest = new PutObjectRequest(bucket, s3Key, file)
            .withMetadata(objectMetadata)

        s3Client.putObject(putRequest)

        return [s3Uri: "s3://${bucket}/${s3Key}", error: null]
    } catch (Exception e) {
        return [s3Uri: null, error: e.message]
    } finally {
        s3Client.shutdown()
    }
}

/**
 * Performs the actual S3 metadata save operation.
 * Separated to handle non-serializable S3 client objects.
 */
@NonCPS
private performSaveMetadata(String bucket, String s3Key, String json, String region, String accessKey = null, String secretKey = null) {
    def s3Client = buildS3Client(region, accessKey, secretKey)
    def inputStream = null

    try {
        // Create metadata for JSON file
        def objectMetadata = new ObjectMetadata()
        objectMetadata.setContentType('application/json')
        objectMetadata.setContentLength(json.bytes.length)

        // Upload JSON as stream
        inputStream = new ByteArrayInputStream(json.bytes)
        def putRequest = new PutObjectRequest(bucket, s3Key, inputStream, objectMetadata)

        s3Client.putObject(putRequest)

        return [error: null]
    } catch (Exception e) {
        return [error: e.message]
    } finally {
        if (inputStream != null) {
            inputStream.close()
        }
        s3Client.shutdown()
    }
}

/**
 * Performs the actual S3 cleanup operation.
 * Separated to handle non-serializable S3 client objects.
 */
@NonCPS
private performS3Cleanup(String bucket, String prefix, String region, String accessKey = null, String secretKey = null) {
    def s3Client = buildS3Client(region, accessKey, secretKey)
    def deletedCount = 0

    try {
        // List and delete all objects with the prefix
        def listRequest = new ListObjectsV2Request()
            .withBucketName(bucket)
            .withPrefix(prefix)

        def result
        def isTruncated = true

        while (isTruncated) {
            result = s3Client.listObjectsV2(listRequest)

            if (result.getObjectSummaries().size() > 0) {
                // S3 batch delete is limited to 1000 objects per request
                // SDK handles this internally when we pass the collection
                def deleteRequest = new DeleteObjectsRequest(bucket)
                def keysToDelete = result.getObjectSummaries().collect {
                    new DeleteObjectsRequest.KeyVersion(it.getKey())
                }
                deleteRequest.setKeys(keysToDelete)
                def deleteResult = s3Client.deleteObjects(deleteRequest)
                deletedCount += deleteResult.getDeletedObjects().size()
            }

            // Handle pagination for buckets with many objects
            isTruncated = result.isTruncated()
            if (isTruncated) {
                listRequest.setContinuationToken(result.getNextContinuationToken())
            }
        }

        return [deletedCount: deletedCount, error: null]
    } catch (Exception e) {
        return [deletedCount: deletedCount, error: e.message]
    } finally {
        s3Client.shutdown()
    }
}

/**
 * Performs the actual S3 list clusters operation.
 * Separated to handle non-serializable S3 client objects.
 */
@NonCPS
private performListClusters(String bucket, String region, String accessKey = null, String secretKey = null) {
    def s3Client = buildS3Client(region, accessKey, secretKey)

    try {
        def clusters = []
        def prefix = ''  // List from bucket root

        // List objects
        def listRequest = new ListObjectsV2Request()
            .withBucketName(bucket)
            .withPrefix(prefix)

        def result
        def isTruncated = true

        while (isTruncated) {
            result = s3Client.listObjectsV2(listRequest)

            result.getObjectSummaries().each { summary ->
                // Look for either metadata.json or cluster-state.tar.gz
                if (summary.getKey().endsWith('metadata.json') || summary.getKey().endsWith('cluster-state.tar.gz')) {
                    // Extract cluster name from path like "test-cluster-001/metadata.json" or "test-cluster-001/cluster-state.tar.gz"
                    def parts = summary.getKey().split('/')
                    if (parts.length >= 2) {
                        def clusterName = parts[0]
                        if (!clusters.contains(clusterName)) {
                            clusters << clusterName
                        }
                    }
                }
            }

            isTruncated = result.isTruncated()
            if (isTruncated) {
                listRequest.setContinuationToken(result.getNextContinuationToken())
            }
        }

        return [clusters: clusters, error: null]
    } catch (Exception e) {
        return [clusters: [], error: e.message]
    } finally {
        s3Client.shutdown()
    }
}

/**
 * Performs the actual S3 bucket existence check and creation.
 * Separated to handle non-serializable S3 client objects.
 */
@NonCPS
private performEnsureS3BucketExists(String bucketName, String region, String accessKey = null, String secretKey = null) {
    def s3Client = buildS3Client(region, accessKey, secretKey)

    try {
        // Check if bucket exists
        if (!s3Client.doesBucketExistV2(bucketName)) {
            // Create bucket with region specification
            if (region == 'us-east-1') {
                // us-east-1 doesn't require explicit region specification
                s3Client.createBucket(bucketName)
            } else {
                // For other regions, use the overloaded method with region string
                s3Client.createBucket(bucketName, region)
            }

            // Enable versioning for safety
            def versioningConfig = new BucketVersioningConfiguration()
                .withStatus(BucketVersioningConfiguration.ENABLED)
            s3Client.setBucketVersioningConfiguration(
                new SetBucketVersioningConfigurationRequest(
                    bucketName, versioningConfig
                )
            )

            return [created: true, error: null, errorCode: null]
        } else {
            return [created: false, error: null, errorCode: null]
        }
    } catch (AmazonS3Exception e) {
        return [created: false, error: e.message, errorCode: e.getStatusCode()]
    } catch (Exception e) {
        return [created: false, error: e.message, errorCode: null]
    } finally {
        s3Client.shutdown()
    }
}

/**
 * Removes all S3 objects for a cluster from active state.
 * Uses batch deletion for efficiency with large numbers of files.
 *
 * @param params Map containing bucket, clusterName, and region
 */
def cleanup(Map params) {
    openshiftTools.log('INFO', "Cleaning up S3 state for cluster: ${params.clusterName}", params)

    def prefix = "${params.clusterName}/"

    // Perform the S3 cleanup in a separate method to avoid serialization issues
    def result = performS3Cleanup(
        params.bucket,
        prefix,
        params.region,
        params.accessKey,
        params.secretKey
    )

    if (result.error) {
        openshiftTools.log('ERROR', "Failed to cleanup S3 state: ${result.error}", params)
        error "Failed to cleanup S3 state: ${result.error}"
    }

    openshiftTools.log('INFO', "Successfully deleted ${result.deletedCount} objects for cluster: ${params.clusterName}", params)
}

/**
 * Lists all OpenShift clusters by scanning S3 for metadata files.
 *
 * @param params Map containing:
 *   - bucket: S3 bucket name (optional, default: 'openshift-clusters-119175775298-us-east-2')
 *   - region: AWS region (optional, default: 'us-east-2')
 *   - accessKey: AWS access key (optional, uses environment if not provided)
 *   - secretKey: AWS secret key (optional, uses environment if not provided)
 * @return List of cluster names
 */
def listClusters(Map params = [:]) {
    def bucket = params.bucket ?: 'openshift-clusters-119175775298-us-east-2'
    def region = params.region ?: 'us-east-2'
    def accessKey = params.accessKey
    def secretKey = params.secretKey

    // Perform the S3 list operation in a separate method to avoid serialization issues
    def result = performListClusters(bucket, region, accessKey, secretKey)

    if (result.error) {
        openshiftTools.log('ERROR', "Failed to list clusters: ${result.error}", params)
        error "Failed to list clusters from S3: ${result.error}"
    }

    return result.clusters
}

/**
 * Ensures S3 bucket exists, creating it if necessary.
 * Enables versioning for safety and handles region-specific creation.
 *
 * @param bucketName Name of the S3 bucket
 * @param region AWS region for bucket
 * @param accessKey AWS access key (optional, uses environment if not provided)
 * @param secretKey AWS secret key (optional, uses environment if not provided)
 * @throws error if bucket exists but owned by different account
 */
def ensureS3BucketExists(String bucketName, String region, String accessKey = null, String secretKey = null) {
    openshiftTools.log('DEBUG', "Checking if S3 bucket ${bucketName} exists in region ${region}...", [bucket: bucketName, region: region])

    // Perform the S3 bucket operations in a separate method to avoid serialization issues
    def result = performEnsureS3BucketExists(bucketName, region, accessKey, secretKey)

    if (result.error) {
        if (result.errorCode == 409) {
            // 409 Conflict means bucket name is taken globally
            // S3 bucket names must be globally unique across all AWS accounts
            error "S3 bucket ${bucketName} already exists but is owned by another AWS account"
        } else {
            error "Failed to create S3 bucket ${bucketName}: ${result.error}"
        }
    }

    if (result.created) {
        openshiftTools.log('INFO', "S3 bucket ${bucketName} created successfully with versioning enabled", [bucket: bucketName, region: region])
    } else {
        openshiftTools.log('DEBUG', "S3 bucket ${bucketName} already exists", [bucket: bucketName, region: region])
    }
}

