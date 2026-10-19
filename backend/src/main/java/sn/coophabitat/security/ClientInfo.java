package sn.coophabitat.security;

import jakarta.servlet.http.HttpServletRequest;

public final class ClientInfo {

    private ClientInfo() {
    }

    /**
     * Adresse du client. Derrière un proxy inverse, configurer server.forward-headers-strategy=framework
     * pour que getRemoteAddr() reflète l'en-tête X-Forwarded-For de confiance (jamais lu directement ici).
     */
    public static String ip(HttpServletRequest request) {
        return request == null ? null : request.getRemoteAddr();
    }

    public static String userAgent(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String ua = request.getHeader("User-Agent");
        return ua == null ? null : ua.substring(0, Math.min(ua.length(), 300));
    }
}
