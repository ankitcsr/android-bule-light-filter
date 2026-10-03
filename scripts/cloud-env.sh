#!/usr/bin/env bash
# Source this file in Codex cloud before running Gradle or Android SDK tools.
if [[ ! ${AMBER_BASE_JAVA_TOOL_OPTIONS+x} ]]; then
    export AMBER_BASE_JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-}"
fi
export JAVA_TOOL_OPTIONS="$AMBER_BASE_JAVA_TOOL_OPTIONS"
export JAVA_HOME=/workspace/toolchains/jdk17
export ANDROID_HOME=/workspace/toolchains/android-sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME=/workspace/toolchains/android-user
export ANDROID_EMULATOR_HOME="$ANDROID_USER_HOME"
export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
mkdir -p "$ANDROID_USER_HOME" "$ANDROID_AVD_HOME"
export GRADLE_USER_HOME=/workspace/toolchains/gradle-cache
mkdir -p "$GRADLE_USER_HOME/init.d"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/19.0/bin:$PATH"

# Retain Java's standard trust anchors and add the cloud's provided TLS proxy CA.
# Never disable certificate validation. A new task may provide a different CA.
if [[ -n "${CODEX_PROXY_CERT:-}" && -f "$CODEX_PROXY_CERT" ]]; then
    mkdir -p /workspace/toolchains/trust
    cp "$JAVA_HOME/lib/security/cacerts" /workspace/toolchains/trust/cacerts
    "$JAVA_HOME/bin/keytool" -importcert -noprompt -alias codex-network-proxy \
        -file "$CODEX_PROXY_CERT" -keystore /workspace/toolchains/trust/cacerts \
        -storepass changeit >/dev/null 2>&1
    export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djavax.net.ssl.trustStore=/workspace/toolchains/trust/cacerts"
fi

# Java does not automatically use HTTP(S)_PROXY. Pass only host and port;
# credentials, if supplied by the platform, are never copied or logged.
eval "$(python3 - <<'PY'
import os, shlex, urllib.parse
args = []
for scheme in ('http', 'https'):
    value = os.environ.get(scheme.upper() + '_PROXY') or os.environ.get(scheme + '_proxy')
    if value:
        proxy = urllib.parse.urlsplit(value)
        if proxy.hostname:
            args.extend([f'-D{scheme}.proxyHost={proxy.hostname}', f'-D{scheme}.proxyPort={proxy.port or 80}'])
print('export JAVA_TOOL_OPTIONS=' + shlex.quote(os.environ.get('JAVA_TOOL_OPTIONS', '') + ' ' + ' '.join(args)))
PY
)"

# Gradle's daemon must also receive the proxy configuration. Honor the cloud's
# existing NO_PROXY routes (including Maven Central) rather than proxying them.
cat > "$GRADLE_USER_HOME/init.d/cloud-network.gradle" <<'GRADLE'
['http', 'https'].each { scheme ->
    def raw = System.getenv(scheme.toUpperCase() + '_PROXY') ?: System.getenv(scheme + '_proxy')
    if (raw) {
        def proxy = new URI(raw)
        System.setProperty(scheme + '.proxyHost', proxy.host)
        System.setProperty(scheme + '.proxyPort', String.valueOf(proxy.port == -1 ? 80 : proxy.port))
    }
}
def rawBypass = System.getenv('NO_PROXY') ?: System.getenv('no_proxy') ?: ''
def bypass = rawBypass.split(',').collectMany { entry ->
    def host = entry.trim()
    host.startsWith('.') ? [host.substring(1), '*' + host] : [host]
}.findAll { it }.join('|')
if (bypass) {
    System.setProperty('http.nonProxyHosts', bypass)
    System.setProperty('https.nonProxyHosts', bypass)
}
// Optional, official Google-hosted Maven Central mirror for shared-IP 429s.
// Enable only after its hostname is allowed in the environment's network settings.
if (System.getenv('AMBER_USE_CENTRAL_MIRROR') == '1') {
    settingsEvaluated { settings ->
        def useMirror = { repositories ->
            repositories.each { repo ->
                if (repo instanceof org.gradle.api.artifacts.repositories.MavenArtifactRepository
                        && repo.url.host in ['repo.maven.apache.org', 'repo1.maven.org']) {
                    repo.setUrl('https://maven-central.storage-download.googleapis.com/maven2/')
                }
            }
        }
        useMirror(settings.pluginManagement.repositories)
        useMirror(settings.dependencyResolutionManagement.repositories)
    }
}
GRADLE
