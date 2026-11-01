package io.github.jqssun.airplay.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.github.jqssun.airplay.Prefs
import io.github.jqssun.airplay.get
import io.leancast.app.Net

object NetworkDiagnostics {
    fun report(context: Context, running: Boolean): String {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork
        val capabilities = network?.let(manager::getNetworkCapabilities)
        val ip = Net.lanIpv4()
        val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        return buildString {
            appendLine(if (ip == null) "Sem endereço IPv4 local. Conecte a TV ao Wi-Fi ou cabo." else "IP da TV: $ip")
            appendLine(if (running) "Receptor iniciado." else "Receptor parado. Inicie em Conectar.")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) appendLine("VPN ativa. Ela pode impedir a descoberta local.")
            if (prefs[Prefs.USB_ONLY]) appendLine("Descoberta limitada a USB. Para Wi-Fi, selecione Todas as redes nas Configurações.")
            appendLine("AirPlay: ${prefs[Prefs.SERVER_NAME]} • porta ${prefs[Prefs.SERVER_PORT]}")
            appendLine("DLNA: ${if (prefs[Prefs.DLNA_ENABLED]) "ativado" else "desativado"} • FCast: ${if (prefs[Prefs.FCAST_ENABLED]) "ativado" else "desativado"}")
            appendLine()
            appendLine("Não aparece no celular ou Mac?")
            appendLine("• Conecte ambos à mesma rede local. Wi-Fi e cabo podem funcionar juntos.")
            appendLine("• Evite rede de convidados e isolamento entre clientes do roteador.")
            appendLine("• Confira se a VPN ou o firewall do emissor permite a rede local.")
            appendLine("• Descoberta depende de multicast/mDNS. Após trocar de rede, deixe a recuperação automática ligada.")
            appendLine("Não é possível confirmar a rede do emissor sem uma conexão dele; redes diferentes são uma hipótese, não um diagnóstico detectado.")
            appendLine()
            appendLine("Imagem cortada? Use Calibrar os quatro cantos. O painel pode recortar HDMI mesmo quando o app envia o quadro inteiro.")
            appendLine("Legendas SRT/VTT: use CC durante um vídeo por URL. Espelhamento usa as legendas do emissor.")
        }
    }
}
