package com.sskings.agendai.common;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * A "origem" de um pedido para limites e tetos: IPv4 inteiro (o mapeado em IPv6 vira IPv4); IPv6 pelo /64 (o cliente
 * troca de endereço dentro do bloco à vontade).
 */
public final class ClientSource {

    private ClientSource() {}

    public static String of(String ip) {
        if (ip == null || !ip.contains(":")) return String.valueOf(ip);
        try {
            byte[] b = InetAddress.getByName(ip).getAddress();
            if (b.length != 16) return InetAddress.getByAddress(b).getHostAddress();
            return String.format("%02x%02x:%02x%02x:%02x%02x:%02x%02x::/64", b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7]);
        } catch (UnknownHostException e) {
            return ip;
        }
    }
}
