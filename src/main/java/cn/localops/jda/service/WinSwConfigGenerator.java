package cn.localops.jda.service;

public final class WinSwConfigGenerator {
    public String generate(String serviceId, String launcherScript, String workingDirectory) {
        return """
                <service>
                  <id>%s</id>
                  <name>Jenkins Agent（%s）</name>
                  <description>Jenkins一键部署助手管理的 WebSocket Agent</description>
                  <executable>powershell.exe</executable>
                  <arguments>-NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File &quot;%s&quot;</arguments>
                  <workingdirectory>%s</workingdirectory>
                  <startmode>Automatic</startmode>
                  <onfailure action="restart" delay="10 sec"/>
                  <stoptimeout>30 sec</stoptimeout>
                  <logpath>%%BASE%%\\logs</logpath>
                  <log mode="roll-by-size">
                    <sizeThreshold>10240</sizeThreshold>
                    <keepFiles>5</keepFiles>
                  </log>
                </service>
                """.formatted(
                xml(serviceId),
                xml(serviceId),
                xml(launcherScript),
                xml(workingDirectory));
    }

    public String launcherScript(String configFile) {
        String escapedConfig = configFile.replace("'", "''");
        return """
                $ErrorActionPreference = 'Stop'
                $configPath = '%s'
                $values = @{}
                Get-Content -LiteralPath $configPath -Encoding UTF8 | ForEach-Object {
                    $line = $_.Trim()
                    if ($line -and -not $line.StartsWith('#')) {
                        $separator = $line.IndexOf('=')
                        if ($separator -gt 0) {
                            $values[$line.Substring(0, $separator).Trim()] = $line.Substring($separator + 1)
                        }
                    }
                }

                $required = @('AGENT_JAVA_EXE', 'AGENT_JAR', 'AGENT_SECRET', 'AGENT_NAME', 'AGENT_WORK_DIR', 'AGENT_TRUSTSTORE', 'AGENT_TRUSTSTORE_PASSWORD', 'JENKINS_URL')
                foreach ($key in $required) {
                    if (-not $values.ContainsKey($key) -or [string]::IsNullOrWhiteSpace($values[$key])) {
                        throw "Agent configuration is incomplete: $key"
                    }
                }

                & $values['AGENT_JAVA_EXE'] `
                    "-Djavax.net.ssl.trustStore=$($values['AGENT_TRUSTSTORE'])" `
                    "-Djavax.net.ssl.trustStorePassword=$($values['AGENT_TRUSTSTORE_PASSWORD'])" `
                    '-Djavax.net.ssl.trustStoreType=PKCS12' `
                    -jar $values['AGENT_JAR'] `
                    -url $values['JENKINS_URL'] `
                    -secret $values['AGENT_SECRET'] `
                    -name $values['AGENT_NAME'] `
                    -webSocket `
                    -workDir $values['AGENT_WORK_DIR']
                exit $LASTEXITCODE
                """.formatted(escapedConfig);
    }

    public String serviceId(String agentName) {
        String suffix = agentName.replaceAll("[^A-Za-z0-9]", "");
        if (suffix.length() > 32) {
            suffix = suffix.substring(suffix.length() - 32);
        }
        return "JenkinsAgent_" + suffix;
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
