package com.example.helloworld;

import android.os.RemoteException;
import android.util.Log;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class UserService extends IUserService.Stub {

    private static final String TAG = "UserService";

    private WebSocketClient webSocketClient;
    private String currentWsUrl;
    private String currentKey;
    private String[] packageNames;
    private volatile boolean shouldReconnect = true;
    private ScheduledExecutorService reconnectExecutor;

    public UserService() {
        Log.d(TAG, "UserService created");
    }

    @Override
    public void connect(String wsUrl, String key, String packageList) throws RemoteException {
        Log.d(TAG, "connect called: url=" + wsUrl + ", key=" + key);
        this.currentWsUrl = wsUrl;
        this.currentKey = key;

        if (packageList != null && !packageList.trim().isEmpty()) {
            String[] lines = packageList.split("\n");
            List<String> validPackages = new ArrayList<>();
            for (String line : lines) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    validPackages.add(trimmed);
                }
            }
            this.packageNames = validPackages.toArray(new String[0]);
        } else {
            this.packageNames = new String[0];
        }

        shouldReconnect = true;
        startWebSocket();
    }

    @Override
    public void disconnect() throws RemoteException {
        Log.d(TAG, "disconnect called");
        shouldReconnect = false;
        if (reconnectExecutor != null && !reconnectExecutor.isShutdown()) {
            reconnectExecutor.shutdownNow();
        }
        if (webSocketClient != null) {
            try {
                webSocketClient.closeBlocking();
            } catch (Exception e) {
                Log.e(TAG, "Error closing WebSocket", e);
            }
            webSocketClient = null;
        }
    }

    @Override
    public void executeAction(String action, String key) throws RemoteException {
        Log.d(TAG, "executeAction: action=" + action + ", key=" + key);
        if (!key.equals(currentKey)) {
            Log.w(TAG, "Invalid key, ignoring action");
            return;
        }
        if ("start".equals(action)) {
            unsuspendPackages();
        } else if ("stop".equals(action)) {
            suspendPackages();
        }
    }

    @Override
    public boolean isConnected() throws RemoteException {
        return webSocketClient != null && webSocketClient.isOpen();
    }

    @Override
    public void destroy() throws RemoteException {
        Log.d(TAG, "destroy called");
        shouldReconnect = false;
        if (reconnectExecutor != null && !reconnectExecutor.isShutdown()) {
            reconnectExecutor.shutdownNow();
        }
        if (webSocketClient != null) {
            try {
                webSocketClient.closeBlocking();
            } catch (Exception e) {
                Log.e(TAG, "Error closing WebSocket on destroy", e);
            }
        }
    }

    private void startWebSocket() {
        if (webSocketClient != null) {
            try {
                webSocketClient.closeBlocking();
            } catch (Exception e) {
                Log.e(TAG, "Error closing existing WebSocket", e);
            }
        }

        try {
            URI uri = new URI(currentWsUrl);
            webSocketClient = new WebSocketClient(uri) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    Log.d(TAG, "WebSocket connected");
                    try {
                        JSONObject registerMsg = new JSONObject();
                        registerMsg.put("type", "register");
                        registerMsg.put("key", currentKey);
                        send(registerMsg.toString());
                    } catch (Exception e) {
                        Log.e(TAG, "Error sending register message", e);
                    }
                }

                @Override
                public void onMessage(String message) {
                    Log.d(TAG, "WebSocket message received: " + message);
                    handleMessage(message);
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    Log.d(TAG, "WebSocket closed: code=" + code + ", reason=" + reason + ", remote=" + remote);
                    scheduleReconnect();
                }

                @Override
                public void onError(Exception ex) {
                    Log.e(TAG, "WebSocket error", ex);
                    scheduleReconnect();
                }
            };

            webSocketClient.setConnectionLostTimeout(30);
            webSocketClient.connect();
        } catch (Exception e) {
            Log.e(TAG, "Error creating WebSocket client", e);
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!shouldReconnect) return;

        if (reconnectExecutor == null || reconnectExecutor.isShutdown()) {
            reconnectExecutor = Executors.newSingleThreadScheduledExecutor();
        }

        reconnectExecutor.schedule(() -> {
            if (shouldReconnect) {
                Log.d(TAG, "Attempting to reconnect...");
                startWebSocket();
            }
        }, 5, TimeUnit.SECONDS);
    }

    private void handleMessage(String message) {
        try {
            JSONObject json = new JSONObject(message);
            String action = json.optString("action", "");
            String key = json.optString("key", "");

            if (key.isEmpty() || !key.equals(currentKey)) {
                Log.w(TAG, "Invalid or missing key in message");
                sendResponse("error", "Invalid key");
                return;
            }

            switch (action) {
                case "start":
                    unsuspendPackages();
                    sendResponse("success", "Packages unsuspended");
                    break;
                case "stop":
                    suspendPackages();
                    sendResponse("success", "Packages suspended");
                    break;
                default:
                    Log.w(TAG, "Unknown action: " + action);
                    sendResponse("error", "Unknown action: " + action);
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling message", e);
            sendResponse("error", "Invalid message format");
        }
    }

    private void sendResponse(String status, String message) {
        if (webSocketClient != null && webSocketClient.isOpen()) {
            try {
                JSONObject response = new JSONObject();
                response.put("status", status);
                response.put("message", message);
                webSocketClient.send(response.toString());
            } catch (Exception e) {
                Log.e(TAG, "Error sending response", e);
            }
        }
    }

    private void suspendPackages() {
        if (packageNames == null || packageNames.length == 0) {
            Log.w(TAG, "No packages to suspend");
            return;
        }

        for (String pkg : packageNames) {
            String cmd = "pm suspend " + pkg;
            executeShellCommand(cmd);
        }
        Log.d(TAG, "All packages suspended");
    }

    private void unsuspendPackages() {
        if (packageNames == null || packageNames.length == 0) {
            Log.w(TAG, "No packages to unsuspend");
            return;
        }

        for (String pkg : packageNames) {
            String cmd = "pm unsuspend " + pkg;
            executeShellCommand(cmd);
        }
        Log.d(TAG, "All packages unsuspended");
    }

    private String executeShellCommand(String command) {
        StringBuilder output = new StringBuilder();
        try {
            Process process = Runtime.getRuntime().exec(command);
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));

            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            while ((line = errorReader.readLine()) != null) {
                output.append("ERR: ").append(line).append("\n");
            }

            int exitCode = process.waitFor();
            Log.d(TAG, "Command: " + command + " | Exit: " + exitCode + " | Output: " + output.toString().trim());

            reader.close();
            errorReader.close();
        } catch (Exception e) {
            Log.e(TAG, "Error executing command: " + command, e);
            output.append("Exception: ").append(e.getMessage());
        }
        return output.toString().trim();
    }
}