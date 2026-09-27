package com.dsh.xspamblock;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;

import de.robv.android.xposed.XposedBridge;

/**
 * Tiny loopback HTTP service that backs the 屏蔽 control.
 *
 * The control links to http://127.0.0.1:<port>/mute?h=<handle>. Because that address is
 * genuinely reachable, X treats it as a normal link and opens it the way it opens any
 * other link - no reliance on the module intercepting an activity start. Whichever path
 * wins, the request that arrives here is what records the block.
 *
 * The process only ever binds the loopback interface, so nothing outside the device can
 * reach it.
 */
final class LocalServer {

    private static final String PATH = "/mute";
    private static volatile int sPort = -1;

    private LocalServer() {}

    static int port() {
        return sPort;
    }

    /** Real, resolvable URL for a handle, or null when the service is not up yet. */
    static String urlFor(String handle) {
        int p = sPort;
        if (p <= 0) return null;
        return "http://127.0.0.1:" + p + PATH + "?h=" + handle;
    }

    static void start() {
        if (sPort > 0) return;
        try {
            ServerSocket server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
            sPort = server.getLocalPort();
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    acceptLoop(server);
                }
            }, "XSBlock-http");
            t.setDaemon(true);
            t.start();
            XposedBridge.log(ModuleMain.TAG + ": local mute service listening on 127.0.0.1:" + sPort);
        } catch (Throwable t) {
            XposedBridge.log(ModuleMain.TAG + ": local mute service failed to start: " + t);
        }
    }

    private static void acceptLoop(ServerSocket server) {
        while (true) {
            Socket socket = null;
            try {
                socket = server.accept();
                handle(socket);
            } catch (Throwable t) {
                // Keep serving; a failed connection must not kill the loop.
            } finally {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    private static void handle(Socket socket) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
        String requestLine = in.readLine();
        if (requestLine == null) return;

        String handle = null;
        String[] parts = requestLine.split(" ");
        if (parts.length >= 2 && parts[1].startsWith(PATH)) {
            int q = parts[1].indexOf('?');
            if (q >= 0) {
                for (String pair : parts[1].substring(q + 1).split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq > 0 && "h".equals(pair.substring(0, eq))) {
                        handle = URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                    }
                }
            }
        }

        String body;
        if (handle != null && !handle.isEmpty()) {
            XposedBridge.log(ModuleMain.TAG + ": 屏蔽 link visited for @" + handle);
            MuteAction.perform(handle);
            body = page("\u5df2\u5c4f\u853d", "@" + handle + " \u5df2\u52a0\u5165\u5c4f\u853d\u5173\u952e\u8bcd");
        } else {
            body = page("X \u5783\u573e\u5e16\u5c4f\u853d", "\u672c\u670d\u52a1\u4ec5\u4f9b\u6a21\u5757\u5185\u90e8\u4f7f\u7528");
        }
        byte[] bytes = body.getBytes("UTF-8");
        OutputStream out = socket.getOutputStream();
        out.write(("HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n").getBytes("UTF-8"));
        out.write(bytes);
        out.flush();
    }

    private static String page(String title, String message) {
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + title + "</title></head>"
                + "<body style=\"margin:0;background:#111;color:#eee;font-family:sans-serif;"
                + "display:flex;align-items:center;justify-content:center;height:100vh;text-align:center\">"
                + "<div><div style=\"font-size:44px\">\ud83d\udeab</div>"
                + "<p style=\"font-size:20px;margin:12px 0 6px\">" + title + "</p>"
                + "<p style=\"color:#999;font-size:14px\">" + message + "</p></div>"
                + "</body></html>";
    }
}
