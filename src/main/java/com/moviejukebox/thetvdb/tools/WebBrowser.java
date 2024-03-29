/*
 *      Copyright (c) 2004-2011 YAMJ Members
 *      http://code.google.com/p/moviejukebox/people/list 
 *  
 *      Web: http://code.google.com/p/moviejukebox/
 *  
 *      This software is licensed under a Creative Commons License
 *      See this page: http://code.google.com/p/moviejukebox/wiki/License
 *  
 *      For any reuse or distribution, you must make clear to others the 
 *      license terms of this work.  
 */
package com.moviejukebox.thetvdb.tools;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import com.moviejukebox.thetvdb.TheTVDB;

/**
 * Web browser with simple cookies support
 */
public final class WebBrowser {
    private static Logger logger = TheTVDB.getLogger();
    
    private static Map<String, String> browserProperties = new HashMap<String, String>();
    private static Map<String, Map<String, String>> cookies;
    private static String proxyHost = null;
    private static String proxyPort = null;
    private static String proxyUsername = null;
    private static String proxyPassword = null;
    private static String proxyEncodedPassword = null;
    private static int webTimeoutConnect = 25000;   // 25 second timeout
    private static int webTimeoutRead = 90000;      // 90 second timeout

    static {
        browserProperties.put("User-Agent", "Mozilla/5.25 Netscape/5.0 (Windows; I; Win95)");
        cookies = new HashMap<String, Map<String, String>>();
    }
    
    // Hide the constructor
    protected WebBrowser() {
        // prevents calls from subclass
        throw new UnsupportedOperationException();
    }

    public static String request(String url) throws IOException {
        return request(new URL(url));
    }
    
    public static URLConnection openProxiedConnection(URL url) throws IOException {
        if (proxyHost != null) {
            System.getProperties().put("proxySet", "true");
            System.getProperties().put("proxyHost", proxyHost);
            System.getProperties().put("proxyPort", proxyPort);
        }
        
        URLConnection cnx = url.openConnection();
        
        if (proxyUsername != null) {
            cnx.setRequestProperty("Proxy-Authorization", proxyEncodedPassword);
        }
        
        return cnx;
    }

    public static String request(URL url) throws IOException {
        StringWriter content = null;

        try {
            content = new StringWriter();

            BufferedReader in = null;
            URLConnection cnx = null;
            try {
                cnx = openProxiedConnection(url);

                sendHeader(cnx);
                readHeader(cnx);

                //in = new BufferedReader(new InputStreamReader(cnx.getInputStream(), getCharset(cnx)));
                in = new BufferedReader(new InputStreamReader(cnx.getInputStream(), "UTF-8"));
                String line;
                while ((line = in.readLine()) != null) {
                    content.write(line);
                }
            } finally {
                if (in != null) {
                    in.close();
                }

                if (cnx != null) {
                    if(cnx instanceof HttpURLConnection) {
                        ((HttpURLConnection)cnx).disconnect();
                    } else {
                        logger.warning("Warning: Connection not closed!");
                    }
                }
            }
            return content.toString();
        } finally {
            if (content != null) {
                content.close();
            }
        }
    }

    private static void sendHeader(URLConnection cnx) {
        // send browser properties
        for (Map.Entry<String, String> browserProperty : browserProperties.entrySet()) {
            cnx.setRequestProperty(browserProperty.getKey(), browserProperty.getValue());
        }
        // send cookies
        String cookieHeader = createCookieHeader(cnx);
        if (!cookieHeader.isEmpty()) {
            cnx.setRequestProperty("Cookie", cookieHeader);
        }
    }

    private static String createCookieHeader(URLConnection cnx) {
        String host = cnx.getURL().getHost();
        StringBuilder cookiesHeader = new StringBuilder();
        for (Map.Entry<String, Map<String, String>> domainCookies : cookies.entrySet()) {
            if (host.endsWith(domainCookies.getKey())) {
                for (Map.Entry<String, String> cookie : domainCookies.getValue().entrySet()) {
                    cookiesHeader.append(cookie.getKey());
                    cookiesHeader.append("=");
                    cookiesHeader.append(cookie.getValue());
                    cookiesHeader.append(";");
                }
            }
        }
        if (cookiesHeader.length() > 0) {
            // remove last ; char
            cookiesHeader.deleteCharAt(cookiesHeader.length() - 1);
        }
        return cookiesHeader.toString();
    }

    private static void readHeader(URLConnection cnx) {
        // read new cookies and update our cookies
        for (Map.Entry<String, List<String>> header : cnx.getHeaderFields().entrySet()) {
            if ("Set-Cookie".equals(header.getKey())) {
                for (String cookieHeader : header.getValue()) {
                    String[] cookieElements = cookieHeader.split(" *; *");
                    if (cookieElements.length >= 1) {
                        String[] firstElem = cookieElements[0].split(" *= *");
                        String cookieName = firstElem[0];
                        String cookieValue = firstElem.length > 1 ? firstElem[1] : null;
                        String cookieDomain = null;
                        // find cookie domain
                        for (int i = 1; i < cookieElements.length; i++) {
                            String[] cookieElement = cookieElements[i].split(" *= *");
                            if ("domain".equals(cookieElement[0])) {
                                cookieDomain = cookieElement.length > 1 ? cookieElement[1] : null;
                                break;
                            }
                        }
                        if (cookieDomain == null) {
                            // if domain isn't set take current host
                            cookieDomain = cnx.getURL().getHost();
                        }
                        Map<String, String> domainCookies = cookies.get(cookieDomain);
                        if (domainCookies == null) {
                            domainCookies = new HashMap<String, String>();
                            cookies.put(cookieDomain, domainCookies);
                        }
                        // add or replace cookie
                        domainCookies.put(cookieName, cookieValue);
                    }
                }
            }
        }
    }

/*
    @SuppressWarnings("unused")
    private static Charset getCharset(URLConnection cnx) {
        Charset charset = null;
        // content type will be string like "text/html; charset=UTF-8" or "text/html"
        String contentType = cnx.getContentType();
        if (contentType != null) {
            // changed 'charset' to 'harset' in regexp because some sites send 'Charset'
            Matcher m = Pattern.compile("harset *=[ '\"]*([^ ;'\"]+)[ ;'\"]*").matcher(contentType);
            if (m.find()) {
                String encoding = m.group(1);
                try {
                    charset = Charset.forName(encoding);
                } catch (UnsupportedCharsetException e) {
                    // there will be used default charset
                }
            }
        }
        if (charset == null) {
            charset = Charset.defaultCharset();
        }
        
        return charset;
    }
*/
    
    public static String getProxyHost() {
        return proxyHost;
    }

    public static void setProxyHost(String tvdbProxyHost) {
        WebBrowser.proxyHost = tvdbProxyHost;
    }

    public static String getProxyPort() {
        return proxyPort;
    }

    public static void setProxyPort(String tvdbProxyPort) {
        WebBrowser.proxyPort = tvdbProxyPort;
    }

    public static String getTvdbProxyUsername() {
        return proxyUsername;
    }

    public static void setProxyUsername(String tvdbProxyUsername) {
        WebBrowser.proxyUsername = tvdbProxyUsername;
    }

    public static String getProxyPassword() {
        return proxyPassword;
    }

    public static void setProxyPassword(String tvdbProxyPassword) {
        WebBrowser.proxyPassword = tvdbProxyPassword;
        
        if (proxyUsername != null) {
            proxyEncodedPassword = proxyUsername + ":" + tvdbProxyPassword;
            proxyEncodedPassword = Base64.base64Encode(proxyEncodedPassword);
        }
    }

    public static int getWebTimeoutConnect() {
        return webTimeoutConnect;
    }

    public static int getWebTimeoutRead() {
        return webTimeoutRead;
    }

    public static void setWebTimeoutConnect(int webTimeoutConnect) {
        WebBrowser.webTimeoutConnect = webTimeoutConnect;
    }

    public static void setWebTimeoutRead(int webTimeoutRead) {
        WebBrowser.webTimeoutRead = webTimeoutRead;
    }
}
