package com.echo;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;

public class TraditionalEchoServer {
    public static void main(String[] args) {
        // 1. 监听窗口
        int port = 8888;

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Echo服务器已启动，监听端口" + port);

            while(true) {
                // 2. 建立连接
                Socket clientSocket = serverSocket.accept();
                System.out.println("客户端已连接:" + clientSocket.getInetAddress());

                // 3. 为客户端创建一个线程，来处理任务
                new Thread(() -> handClient(clientSocket)).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void handClient(Socket socket) {
        try (
                // 获取输入流：读取客户端的消息
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                // 获取输出流：向客户端发送消息
                BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
        ) {
            // 读取客户端发过来的消息
            String getLine;
            while ((getLine = in.readLine()) != null) {
                // 读到什么消息就返回什么消息
                out.write("Echo" + getLine);
                out.newLine();
                out.flush();
                System.out.println("收到消息: " + getLine + " 已返回");

                if ("bye".equalsIgnoreCase(getLine)) break;
            }
        } catch (IOException e) {
            System.out.println("客户端链接中断");
        }
    }
}
