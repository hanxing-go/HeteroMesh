package com.echo;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VirtualThreadEchoServer {
    public static void main(String[] args) {
        // 1. 监听窗口
        int port = 8888;
        try (ServerSocket socket = new ServerSocket(port)) {
            // 2. 建立连接
            System.out.println("虚拟线程Echo服务器启动，监听端口8888");
            ExecutorService virtualService = Executors.newVirtualThreadPerTaskExecutor();
            while (true) {
                Socket clientSocket = socket.accept();
                System.out.println("客户端已连接: " + clientSocket.getInetAddress());
                
                virtualService.submit(() -> handleClient(clientSocket));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        // 3. 为客户端创建一个线程，来处理任务
        
    }

    private static void handleClient(Socket clientSocket) {
        try (
                // 获取输入流：读取客户端的消息
                BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                // 获取输出流：返回客户端的消息
                BufferedWriter out = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream()));
        ) {
            String getLine;
            while ((getLine = in.readLine()) != null) {
                System.out.println("收到消息: " + getLine);
                out.write("Echo:" + getLine);
                out.newLine();
                out.flush();
                System.out.println(getLine + " 消息已返回");

                if ("bye".equalsIgnoreCase(getLine)) break;
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
        // 获取输出流：向客户端发送消息
    }
}
