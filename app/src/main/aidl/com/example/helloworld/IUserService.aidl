package com.example.helloworld;

interface IUserService {
    void destroy() = 16777114;
    void connect(String wsUrl, String key, String packageList) = 1;
    void disconnect() = 2;
    void executeAction(String action, String key) = 3;
    boolean isConnected() = 4;
}