package org.ywb.java.nio.demo;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Set;

/**
 * @author wenbiao.yang created on 2019/3/18
 */
public class NioServer {

    private ServerSocketChannel serverSocketChannel;
    private Selector selector;

    public NioServer() throws Exception {
        serverSocketChannel = ServerSocketChannel.open();
        serverSocketChannel.configureBlocking(false);
        serverSocketChannel.socket().bind(new InetSocketAddress(6666));
        selector = Selector.open();
        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
        System.out.println("Server start up...");
        handleKeys();
    }

    private void handleKeys() throws IOException {
        while (true) {
            int keys = selector.select(30 * 1000L);
            if (keys == 0) {
                continue;
            }
            System.out.println("选择 Channel 数量：" + keys);
            Set<SelectionKey> selectionKeys = selector.selectedKeys();
            Iterator<SelectionKey> it = selectionKeys.iterator();
            if (it.hasNext()) {
                SelectionKey selectionKey = it.next();
                it.remove();
                if (!selectionKey.isValid()) {
                    continue;
                }
                handleKey(selectionKey);
            }
        }
    }

    private void handleKey(SelectionKey selectionKey) throws IOException {
        if (selectionKey.isAcceptable()) {
            handleAcceptableKey(selectionKey);
        }

        if (selectionKey.isReadable()) {
            handleReadableKey(selectionKey);
        }

        if (selectionKey.isWritable()) {
            handleWritableKey(selectionKey);
        }
    }

    private void handleWritableKey(SelectionKey selectionKey) throws IOException {
        SocketChannel socket = (SocketChannel) selectionKey.channel();
        ArrayList<String> attachQueue = (ArrayList<String>) selectionKey.attachment();
        for (String attach : attachQueue) {
            System.out.println("写入的数据 " + attach);
            CodecUtil.write(socket, attach);
        }
        attachQueue.clear();
        socket.register(selector, SelectionKey.OP_READ, attachQueue);
    }

    private void handleReadableKey(SelectionKey selectionKey) throws ClosedChannelException {
        SocketChannel socket = (SocketChannel) selectionKey.channel();
        ByteBuffer byteBuffer = CodecUtil.read(socket);
        if (byteBuffer == null) {
            System.out.println("断开 channel");
            socket.register(selector, SelectionKey.OP_READ);
            return;
        }

        if (byteBuffer.position() > 0) {
            String content = CodecUtil.newString(byteBuffer);
            System.out.println("read content = " + content);
            ArrayList<String> attachQueue = (ArrayList<String>) selectionKey.attachment();
            attachQueue.add("response: " + content);
            socket.register(selector, SelectionKey.OP_WRITE, selectionKey.attachment());
        }
    }

    private void handleAcceptableKey(SelectionKey selectionKey) throws IOException {
        SocketChannel socket = ((ServerSocketChannel) selectionKey.channel()).accept();
        socket.configureBlocking(false);
        System.out.println("接受新的channel");
        socket.register(selector, SelectionKey.OP_READ, new ArrayList<String>());
    }

    public static void main(String[] args) throws Exception {
        NioServer nioServer = new NioServer();
    }
}
