// OpenShift tools installation and utilities library

/**
 * Logging utility with configurable log levels.
 * Provides structured logging with timestamps and severity levels.
 *
 * @param level Log level (DEBUG, INFO, WARN, ERROR)
 * @param message Log message
 * @param params Optional parameters map containing logLevel setting
 */
def log(String level, String message, Map params = [:]) {
    def logLevels = ['DEBUG': 0, 'INFO': 1, 'WARN': 2, 'ERROR': 3]
    def currentLevel = params.logLevel ?: env.OPENSHIFT_LOG_LEVEL ?: 'INFO'

    if (!logLevels.containsKey(currentLevel)) {
        currentLevel = 'INFO'
    }

    if (logLevels[level] >= logLevels[currentLevel]) {
        def timestamp = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format(new Date())
        echo "[${timestamp}] [${level}] ${message}"
    }
}

/**
 * Installs OpenShift CLI tools (oc and openshift-install) and Helm.
 * Handles version resolution from 'latest', 'stable', etc. to specific versions.
 *
 * @param config Map containing:
 *   - openshiftVersion: Version to install (e.g., '4.16.20', 'latest', 'stable')
 * @return String resolved OpenShift version number
 */
def install(Map config) {
    def params = [
        baseUrl: 'https://mirror.openshift.com/pub/openshift-v4/clients/ocp'
    ] + config

    if (!params.openshiftVersion) {
        error 'Missing required parameter: openshiftVersion'
    }

    log('INFO', "Installing OpenShift tools version: ${params.openshiftVersion}", params)

    try {
        def resolvedVersion = resolveVersion(params.openshiftVersion, params.baseUrl)
        log('DEBUG', "Resolved OpenShift version: ${resolvedVersion}", params)

        def downloadUrl = "${params.baseUrl}/${resolvedVersion}"

        // Fetch checksums using native Groovy
        log('DEBUG', 'Fetching OpenShift checksums...')
        def checksumUrl = "${downloadUrl}/sha256sum.txt"
        def checksumMap = [:]

        try {
            // Fetch and parse the sha256sum.txt file
            // Format: <checksum>  <filename>
            // Example content:
            //   7549bd34267d297d86d8cfeb35e777bec62382ccddf1e4872e77fb9dbb1bea03  openshift-install-linux-4.16.20.tar.gz
            //   7762c0ad23897772887f0d74e7593005565738fa660765a7e8110974fa4a12d8  openshift-client-linux-4.16.20.tar.gz
            def checksums = new URL(checksumUrl).text
            checksums.split('\n').each { line ->
                if (line.trim()) {
                    def parts = line.split(/\s+/)
                    if (parts.length >= 2) {
                        // parts[0] = checksum, parts[1] = filename
                        checksumMap[parts[1]] = parts[0]
                    }
                }
            }
        } catch (Exception e) {
            error("Failed to fetch OpenShift checksums: ${e.message}")
        }

        // Get checksums for required files
        def installerFile = "openshift-install-linux-${resolvedVersion}.tar.gz"
        def clientFile = "openshift-client-linux-${resolvedVersion}.tar.gz"

        def installerChecksum = checksumMap[installerFile]
        def clientChecksum = checksumMap[clientFile]

        if (!installerChecksum || !clientChecksum) {
            error("Failed to find checksums for OpenShift files: installer=${installerChecksum ? 'found' : 'missing'}, client=${clientChecksum ? 'found' : 'missing'}")
        }

        log('DEBUG', "Retrieved checksums - installer: ${installerChecksum}, client: ${clientChecksum}")

        sh """
            # Create tools directory
            # Using ~/.local/bin to avoid requiring sudo permissions
            mkdir -p \$HOME/.local/bin

            # Download and verify OpenShift installer
            echo "Downloading OpenShift installer..."
            curl -sL -o ${installerFile} ${downloadUrl}/${installerFile}
            echo "${installerChecksum}  ${installerFile}" | sha256sum -c -
            tar xzf ${installerFile} -C \$HOME/.local/bin
            rm -f ${installerFile}

            # Download and verify OpenShift CLI
            echo "Downloading OpenShift CLI..."
            curl -sL -o ${clientFile} ${downloadUrl}/${clientFile}
            echo "${clientChecksum}  ${clientFile}" | sha256sum -c -
            tar xzf ${clientFile} -C \$HOME/.local/bin
            rm -f ${clientFile}

            # Make sure binaries are executable
            chmod +x \$HOME/.local/bin/openshift-install
            chmod +x \$HOME/.local/bin/oc

            # Add to PATH
            export PATH="\$HOME/.local/bin:\$PATH"

            # Verify installation
            echo "Verifying installation..."
            \$HOME/.local/bin/openshift-install version
            \$HOME/.local/bin/oc version --client
        """

        // Install Helm if not present
        installHelm()

        return resolvedVersion
    } catch (Exception e) {
        error "Failed to install OpenShift tools: ${e.message}"
    }
}

/**
 * Resolves OpenShift version aliases to specific version numbers.
 * Queries OpenShift release API for latest versions.
 *
 * @param version String version alias ('latest', 'stable', 'fast', 'candidate', 'eus-X.Y')
 * @param baseUrl Base URL for OpenShift mirror
 * @return String specific version number (e.g., '4.16.20')
 */
def resolveVersion(String version, String baseUrl) {
    if (version.matches(/^[0-9]+\.[0-9]+\.[0-9]+$/)) {
        // Already a specific version
        return version
    }

    if (version.matches(/^[0-9]+\.[0-9]+$/)) {
        // Minor version specified, get latest patch
        return getLatestPatchVersion(version, baseUrl)
    }

    // Handle channel versions (latest, stable, fast, candidate, eus)
    def channelVersion = getChannelVersion(version)
    if (channelVersion) {
        return channelVersion
    }

    error "Unable to resolve OpenShift version: ${version}"
}

/**
 * Gets the latest patch version for a given minor version.
 * Uses Groovy's built-in URL parsing instead of fragile shell commands.
 *
 * @param minorVersion Minor version (e.g., '4.16')
 * @param baseUrl Base URL for OpenShift mirror
 * @return String latest patch version or null if not found
 */
def getLatestPatchVersion(String minorVersion, String baseUrl) {
    try {
        // Fetch the directory listing
        def htmlContent = new URL("${baseUrl}/").text

        // Extract version directories using regex
        // Looking for patterns like href="4.16.20/"
        def pattern = ~/href="(${minorVersion}\.[0-9]+)\//
        def versions = []

        htmlContent.eachMatch(pattern) { match ->
            versions << match[1]
        }

        if (versions.isEmpty()) {
            error "No patch versions found for ${minorVersion}"
        }

        // Sort versions using Groovy's natural version comparison
        versions = versions.sort(false) { a, b ->
            def aParts = a.split('\\.').collect { it as int }
            def bParts = b.split('\\.').collect { it as int }
            [aParts, bParts].transpose().findResult { x, y -> x <=> y ?: null } ?: 0
        }

        return versions.last()
    } catch (Exception e) {
        error "Failed to get latest patch version for ${minorVersion}: ${e.message}"
    }
}

/**
 * Retrieves the current version for an OpenShift release channel.
 * Queries the official OpenShift release API.
 *
 * @param channel Release channel name ('stable', 'fast', 'candidate', 'eus-X.Y')
 * @return String version number for the channel
 */
def getChannelVersion(String channel) {
    def validChannels = ['latest', 'stable', 'fast', 'candidate']
    def channelName = channel

    // Handle eus- prefix
    if (channel.startsWith('eus-')) {
        channelName = 'eus'
    }

    if (!validChannels.contains(channelName) && channelName != 'eus') {
        return null
    }

    try {
        // For 'latest', we need to find the newest stable channel
        // OpenShift doesn't have a 'latest' channel, so we map it to the newest stable
        if (channel == 'latest') {
            // Try channels from newest to oldest until we find one with content
            def majorMinorVersions = ['4.19', '4.18', '4.17', '4.16', '4.15', '4.14']

            for (def version : majorMinorVersions) {
                def output = sh(
                    script: """
                        curl -sH 'Accept: application/json' https://api.openshift.com/api/upgrades_info/v1/graph?channel=stable-${version} | \
                        jq -r '.nodes | map(.version) | sort | .[-1]' 2>/dev/null || echo ''
                    """,
                    returnStdout: true
                ).trim()

                if (output && output != 'null' && output != '') {
                    return output
                }
            }
        } else {
            // For other channels, try to find the newest version across recent minor releases
            def majorMinorVersions = ['4.19', '4.18', '4.17', '4.16']
            def allVersions = []

            for (def version : majorMinorVersions) {
                def output = sh(
                    script: """
                        curl -sH 'Accept: application/json' https://api.openshift.com/api/upgrades_info/v1/graph?channel=${channelName}-${version} | \
                        jq -r '.nodes | map(.version) | .[]' 2>/dev/null || echo ''
                    """,
                    returnStdout: true
                ).trim()

                if (output) {
                    allVersions.addAll(output.split('\n').findAll { it })
                }
            }

            if (allVersions) {
                // Sort versions using Groovy's natural version comparison
                allVersions = allVersions.sort(false) { a, b ->
                    def aParts = a.split('\\.').collect { it as int }
                    def bParts = b.split('\\.').collect { it as int }
                    [aParts, bParts].transpose().findResult { x, y -> x <=> y ?: null } ?: 0
                }
                return allVersions.last()
            }
        }
    } catch (Exception e) {
        log('ERROR', "Failed to get channel version: ${e.message}")
    }

    // Fallback to a known good version
    return env.OPENSHIFT_FALLBACK_VERSION ?: '4.16.45'
}

/**
 * Installs Helm 3 if not already present.
 * Required for deploying PMM and other Helm charts to OpenShift.
 * Installs to user's local bin directory without requiring sudo.
 */
def installHelm() {
    def helmInstalled = sh(
        script: 'command -v helm >/dev/null 2>&1 && echo "true" || echo "false"',
        returnStdout: true
    ).trim()

    if (helmInstalled == 'false') {
        log('INFO', 'Installing Helm...')
        // Install specific Helm version with checksum verification
        def helmVersion = env.HELM_VERSION ?: '3.14.0'

        // Fetch checksum dynamically using native Groovy
        log('DEBUG', "Fetching checksum for Helm v${helmVersion}...")
        def checksumUrl = "https://get.helm.sh/helm-v${helmVersion}-linux-amd64.tar.gz.sha256sum"

        def helmChecksum
        try {
            // Use Groovy's native HTTP capabilities
            def response = new URL(checksumUrl).text
            helmChecksum = response.split(/\s+/)[0].trim()

            if (!helmChecksum || !helmChecksum.matches(/^[a-f0-9]{64}$/)) {
                error("Invalid checksum format received for Helm version ${helmVersion}")
        }

            log('DEBUG', "Retrieved checksum: ${helmChecksum}")
        } catch (Exception e) {
            error("Failed to fetch checksum for Helm version ${helmVersion}. Error: ${e.message}")
    }

        sh """
            # Download Helm binary directly instead of using installer script
            curl -fsSL -o helm.tar.gz https://get.helm.sh/helm-v${helmVersion}-linux-amd64.tar.gz

            # Verify checksum
            echo "${helmChecksum}  helm.tar.gz" | sha256sum -c -

            # Extract helm binary
            tar -xzf helm.tar.gz linux-amd64/helm

            # Install to local bin
            mkdir -p \$HOME/.local/bin
            mv linux-amd64/helm \$HOME/.local/bin/
            chmod +x \$HOME/.local/bin/helm

            # Cleanup
            rm -rf helm.tar.gz linux-amd64/
        """
}

    sh '''
        export PATH="$HOME/.local/bin:$PATH"
        helm version --short
    '''
}

// Utility functions for OpenShift operations
/**
 * Checks the overall status of an OpenShift cluster.
 *
 * @param kubeconfig Path to kubeconfig file
 * @return boolean true if cluster has nodes, false otherwise
 */
def checkClusterStatus(String kubeconfig) {
    try {
        def status = sh(
            script: """
                export KUBECONFIG=${kubeconfig}
                oc get nodes --no-headers | wc -l
            """,
            returnStdout: true
        ).trim()

        return status.toInteger() > 0
    } catch (Exception e) {
        return false
    }
}

/**
 * Waits for an OpenShift cluster to become fully ready.
 * Polls cluster operators until all are available.
 *
 * @param kubeconfig Path to kubeconfig file
 * @param timeoutMinutes Maximum time to wait (default: 45)
 * @return boolean true if cluster is ready, false if timeout
 */
def waitForClusterReady(String kubeconfig, int timeoutMinutes = 45) {
    def endTime = System.currentTimeMillis() + (timeoutMinutes * 60 * 1000)

    while (System.currentTimeMillis() < endTime) {
        if (checkClusterStatus(kubeconfig)) {
            def notReadyNodes = sh(
                script: """
                    export KUBECONFIG=${kubeconfig}
                    oc get nodes --no-headers | grep -E 'NotReady|SchedulingDisabled' | wc -l
                """,
                returnStdout: true
            ).trim()

            if (notReadyNodes == '0') {
                log('INFO', 'All nodes are ready!')
                return true
            }
        }

        log('DEBUG', 'Waiting for cluster to be ready...')
        sleep(30)
    }

    error "Cluster did not become ready within ${timeoutMinutes} minutes"
}

/**
 * Gets the OpenShift version of a running cluster.
 *
 * @param kubeconfig Path to kubeconfig file
 * @return String OpenShift version
 */
def getClusterVersion(String kubeconfig) {
    return sh(
        script: """
            export KUBECONFIG=${kubeconfig}
            oc version -o json | jq -r '.openshiftVersion // .serverVersion.gitVersion'
        """,
        returnStdout: true
    ).trim()
}

/**
 * Lists all nodes in an OpenShift cluster.
 *
 * @param kubeconfig Path to kubeconfig file
 * @return List of node information maps
 */
def getClusterNodes(String kubeconfig) {
    def output = sh(
        script: """
            export KUBECONFIG=${kubeconfig}
            oc get nodes -o json
        """,
        returnStdout: true
    ).trim()

    return readJSON(text: output)
}

/**
 * Backward compatibility method - delegates to install().
 * Allows calling the library directly without method name.
 *
 * @param config Installation configuration map
 * @return Result from install() method
 */
def call(Map config) {
    return install(config)
}

