package com.echo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.*;
import java.util.Scanner;

public class EchoClient {
    public static void main(String[] args) {
        int connectTimeMax = 5000;
        // 1. 首先需要有一个ip地址与端口 建立链接
        String ipSever = "127.0.0.1";
        int port = 8888;
        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(ipSever, 8888), connectTimeMax);
            // 2. 使用IO流来读取
            PrintStream out = new PrintStream(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            Scanner scanner = new Scanner(System.in);
            System.out.println("已连接到服务器，请输入消息，bye退出");

            while (true) {
                String message = scanner.nextLine();
                // 向服务器发送消息
                out.println(message);
                // 打印服务返回的消息
                System.out.println("服务器相应:" + in.readLine());
                if ("bye".equalsIgnoreCase(message)) break;
            }
        } catch (SocketTimeoutException e) {
            System.out.println("连接超时");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }
}
