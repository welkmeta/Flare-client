package flare.client.app.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.util.UUID
import java.security.SecureRandom
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider

object SecurityInitializer {
    private var initialized = false
    fun init() {
        if (!initialized) {
            Security.removeProvider("BC")
            Security.insertProviderAt(BouncyCastleProvider(), 1)
            initialized = true
        }
    }
}

class SshSetupManager(private val context: Context) {

    private val _progress = MutableStateFlow(0)
    val progress: StateFlow<Int> = _progress

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    private fun SSHClient.exec(cmd: String): String {
        var out = ""
        startSession().use { session ->
            val c = session.exec(cmd)
            out = c.inputStream.bufferedReader().readText()
            c.errorStream.bufferedReader().readText()
            c.join()
        }
        return out.trim()
    }

    private fun SSHClient.execWithErr(cmd: String): Pair<String, String> {
        var out = ""
        var err = ""
        startSession().use { session ->
            val c = session.exec(cmd)
            out = c.inputStream.bufferedReader().readText()
            err = c.errorStream.bufferedReader().readText()
            c.join()
        }
        return out.trim() to err.trim()
    }

    suspend fun setupXray(
        host: String,
        sshPort: Int = 22,
        user: String,
        pass: String,
        vpnPort: Int = 443,
        sni: String = "www.google.com"
    ): String? = withContext(Dispatchers.IO) {
        SecurityInitializer.init()
        val ssh = SSHClient()
        try {
            _status.value = "Подключение к серверу..."
            ssh.addHostKeyVerifier(PromiscuousVerifier())
            ssh.connect(host, sshPort)
            ssh.authPassword(user, pass)
            _progress.value = 10

            var xrayPath = ssh.exec("command -v xray || ( [ -x /usr/local/bin/xray ] && echo /usr/local/bin/xray ) || ( [ -x /usr/bin/xray ] && echo /usr/bin/xray)").lines().firstOrNull { it.isNotBlank() }?.trim() ?: ""
            if (xrayPath.isEmpty()) {
                _status.value = "Установка Xray..."
                ssh.exec("sudo -n apt-get update -qq && sudo -n apt-get install -y curl 2>&1")
                ssh.exec("curl -L https://github.com/XTLS/Xray-install/raw/main/install-release.sh | sudo -n bash -s -- install 2>&1")
                xrayPath = "/usr/local/bin/xray"
            }
            _progress.value = 30

            _status.value = "Генерация ключей REALITY..."
            val uuid = UUID.randomUUID().toString()
            val shortId = ByteArray(8).apply { SecureRandom().nextBytes(this) }
                .joinToString("") { "%02x".format(it) }

            val (keyOut, keyErr) = ssh.execWithErr("sudo -n $xrayPath x25519 2>&1")
            val keyLines = keyOut.lines()
            val privateKey = keyLines
                .find { it.contains("Private", ignoreCase = true) && it.contains("key", ignoreCase = true) }
                ?.substringAfter(":")?.trim()
            val publicKey = keyLines
                .find { it.contains("Public", ignoreCase = true) && it.contains("key", ignoreCase = true) }
                ?.substringAfter(":")?.trim()

            if (privateKey.isNullOrEmpty() || publicKey.isNullOrEmpty()) {
                throw Exception("Не удалось получить ключи REALITY.\nВывод: [$keyOut]\nОшибки: [$keyErr]")
            }
            _progress.value = 55

            _status.value = "Настройка конфигурации..."
            val xrayConfig = """
{
  "log": { "loglevel": "warning" },
  "inbounds": [
    {
      "listen": "0.0.0.0",
      "port": $vpnPort,
      "protocol": "vless",
      "settings": {
        "clients": [
          {
            "id": "$uuid",
            "flow": "xtls-rprx-vision"
          }
        ],
        "decryption": "none"
      },
      "streamSettings": {
        "network": "tcp",
        "security": "reality",
        "realitySettings": {
          "show": false,
          "dest": "$sni:443",
          "xver": 0,
          "serverNames": ["$sni"],
          "privateKey": "$privateKey",
          "shortIds": ["", "$shortId"]
        }
      },
      "sniffing": {
        "enabled": true,
        "destOverride": ["http", "tls", "quic"]
      }
    }
  ],
  "outbounds": [
    { "protocol": "freedom", "tag": "direct" },
    { "protocol": "blackhole", "tag": "block" }
  ]
}""".trimIndent()

            val remoteConfigPath = "/usr/local/etc/xray/config.json"
            val configB64 = android.util.Base64.encodeToString(
                xrayConfig.toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP
            )
            ssh.exec("echo '$configB64' | base64 -d | sudo tee $remoteConfigPath > /dev/null")

            val fileSize = ssh.exec("sudo wc -c < $remoteConfigPath 2>&1")
            if (fileSize.trim() == "0" || fileSize.trim().isEmpty()) {
                throw Exception("Файл конфига не был записан на сервер")
            }
            _progress.value = 70

            _status.value = "Перезапуск сервиса Xray..."
            ssh.exec("sudo systemctl enable xray 2>&1")
            ssh.exec("sudo systemctl restart xray 2>&1")
            _progress.value = 80

            _status.value = "Ожидание запуска..."
            Thread.sleep(3000)

            val serviceStatus = ssh.exec("sudo systemctl is-active xray 2>&1")
            if (!serviceStatus.trim().equals("active", ignoreCase = true)) {
                val serviceLogs = ssh.exec("sudo journalctl -u xray -n 40 --no-pager 2>&1")
                throw Exception("Сервис Xray не запустился (статус: $serviceStatus).\nЖурнал:\n${serviceLogs.take(600)}")
            }

            val portCheck = ssh.exec("sudo ss -tlnp 2>/dev/null | grep ':$vpnPort' || echo 'port-check-unavailable'")
            if (!portCheck.contains("$vpnPort") && !portCheck.contains("port-check-unavailable")) {
                throw Exception("Xray запущен, но не слушает порт $vpnPort!")
            }

            _progress.value = 90
            _status.value = "Генерация настроек клиента..."
            val vlessUri = "vless://$uuid@$host:$vpnPort" +
                "?security=reality" +
                "&flow=xtls-rprx-vision" +
                "&sni=$sni" +
                "&pbk=${java.net.URLEncoder.encode(publicKey, "UTF-8")}" +
                "&sid=$shortId" +
                "&fp=chrome" +
                "&type=tcp" +
                "#Flare-$host"

            val parsed = flare.client.app.data.parser.ClipboardParser.buildProfileFromUri(
                vlessUri, subscriptionId = null
            )
            _progress.value = 100
            parsed.configJson ?: ""

        } catch (e: Exception) {
            android.util.Log.e("SshSetupManager", "SSH Setup Error: ${e.message}", e)
            _status.value = "Ошибка: ${e.message?.take(200)}"
            null
        } finally {
            try { ssh.disconnect() } catch (_: Exception) {}
        }
    }
}
