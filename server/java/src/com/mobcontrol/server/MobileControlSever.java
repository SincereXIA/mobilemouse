package com.mobcontrol.server;

import java.awt.AWTException;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.BindException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Enumeration;

import javax.swing.SwingUtilities;

import com.google.zxing.WriterException;
import com.mobcontrol.server.ui.UIFrame;

public class MobileControlSever extends Thread {

    //TODO: connection status management

    private Mode mMode = Mode.QR_SCAN;

    private static final String MULTI_CAST_ADDR = "239.5.6.7";
    private static final int LOCAL_UDP_PORT = 30000;
    private static final int MCAST_CLIENT_PORT = 28960;
    private static final int LOCAL_TCP_PORT = 27015;// control data comes here

    private static final int WAIT_FOR_WAVE_SO_TIMEOUT = 60 * 1000; // milliseconds
    private static final int WAIT_FOR_WAVE_BUFFER_SIZE = 1024; // bytes

    private ServerSocket mServerSocket;
    private String mIpAddr;
    private Robot mRobot;

    private int mMouseX, mMouseY;

    private enum Mode {
        DISCOVERY,
        QR_SCAN
    }

    public MobileControlSever(Mode mode, int port) throws IOException {
        mMode = mode;
        mServerSocket = new ServerSocket();
        try {
            mRobot = new Robot();
        } catch (AWTException e) {
            e.printStackTrace();
        }
        mIpAddr = getMyIp();
        System.out.println("my ip: " + mIpAddr);
        if (mIpAddr == null || mIpAddr.length() == 0) {
            throw new IllegalArgumentException("IP address is null!");
        }

        if (!mServerSocket.isBound()) {
            try {
                mServerSocket.bind(new InetSocketAddress(mIpAddr, port));
            } catch (BindException e) {
                e.printStackTrace();
                // TODO: Address already in use, notify the user that there may
                // be already an server instance running on this computer
            }
        }
        // try {
        // mServerSocket.bind(new InetSocketAddress("172.17.106.48", port));
        // } catch (BindException e) {
        // e.printStackTrace();
        // }
        // mServerSocket.setSoTimeout(10000);
    }

    /**
     * @param x
     *            to x
     * @param y
     *            to y
     */
    private void mouseMove(int x, int y) {
        System.out.println("mouseMove called, x: " + x + ", y: " + y);
        if (mRobot != null) {
            mRobot.mouseMove(x, y);
        }

        updateCurrentMousePosition();
    }

    private void updateCurrentMousePosition() {
        PointerInfo pointerInfo = MouseInfo.getPointerInfo();
        if (pointerInfo != null) {
            Point p = pointerInfo.getLocation();
            if (p != null) {
                mMouseX = (int) p.getX();
                mMouseY = (int) p.getY();
            }
        }
    }

    private void leftClick() {
        System.out.println("leftClick called");
        if (mRobot != null) {
            mRobot.mousePress(InputEvent.BUTTON1_MASK);
            mRobot.mouseRelease(InputEvent.BUTTON1_MASK);
        }
    }

    private void rightClick() {
        System.out.println("rightClick called");
        if (mRobot != null) {
            mRobot.mousePress(InputEvent.BUTTON3_MASK);
            mRobot.mouseRelease(InputEvent.BUTTON3_MASK);
        }
    }

    public void run() {

        updateCurrentMousePosition();

        if (mMode == Mode.DISCOVERY) {
            try {
                waitForClientWave(MULTI_CAST_ADDR, LOCAL_UDP_PORT);
            } catch (IOException e1) {
                e1.printStackTrace();
            }
        } else {
            generateAndShowQRCode();
        }

        Socket server = null;
        try {
            server = mServerSocket.accept();
        } catch (IOException e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        }
        while (true) {
            DataInputStream in = null;
            try {
                System.out.println("Waiting for client on port " + mServerSocket.getLocalPort() + "...");
                // System.out.println("Server ip: "
                // + mServerSocket.getInetAddress());
                System.out.println("Just connected to " + server.getRemoteSocketAddress());
                in = new DataInputStream(server.getInputStream());
                String inStr = in.readUTF();
                System.out.println(inStr);
                handleTouchData(inStr);
            } catch (SocketTimeoutException s) {
                System.out.println("Socket timed out!");
                break;
            } catch (IOException e) {
                //client disconnected
                e.printStackTrace();
                System.out.println("client disconnected");
                //TODO: alert the user
                try {
                    in.close();
                    server.close();
                    in = null;
                    server = null;
                } catch (Exception ee) {
                    e.printStackTrace();
                }
                break;
            }
        }
    }

    private void generateAndShowQRCode() {
        try {
            BufferedImage image = QRUtils.createQRImage(mIpAddr, 300);
            if (image != null) {
                SwingUtilities.invokeLater(new Runnable() {

                    @Override
                    public void run() {
                        UIFrame uiFrame = new UIFrame();
                        uiFrame.showImage(image);
                    }
                });
            }
        } catch (WriterException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    private void handleTouchData(String t) {
        if (t != null && t.length() > 0) {
            String[] segs = t.split(",");
            int segLen = segs.length;
            if (segLen > 1 && "mctd".equals(segs[0])) {
                String type = segs[1];
                if ("L".equals(type)) {
                    leftClick();
                } else if ("R".equals(type)) {
                    rightClick();
                } else if ("M".equals(type)) {
                    if (segLen > 3) {
                        String x = segs[2], y = segs[3];
                        System.out.println("handle move, x: " + x + ", y: " + y);
                        try {
                            int xInt = Integer.valueOf(x);
                            int yInt = Integer.valueOf(y);
                            if (xInt != 0 || yInt != 0) {
                                mouseMove(mMouseX + xInt, mMouseY + yInt);
                            }
                        } catch (NumberFormatException e) {
                            e.printStackTrace();
                        }
                    }
                }
            } else {
                System.out.println(t);
            }
        }
    }

    private String getMyIp() {
        try {
            System.out.println("Full list of Network Interfaces:");
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                System.out.println("    " + intf.getName() + " " + intf.getDisplayName());
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                    InetAddress ia = enumIpAddr.nextElement();
                    String ip = ia.getHostAddress();
                    System.out.println("        " + ip);
                    if (ip != null && (ip.startsWith("192.") || ip.startsWith("172.") || ip.startsWith("10."))) {
                        return ip;
                    }
                }
            }
        } catch (SocketException e) {
            System.out.println(" (error retrieving network interface list)");
        }
        return null;
    }

    private void waitForClientWave(String addr, int port) throws IOException {
        System.out.println("waitForClientWave " + addr + ", " + port);
        InetAddress group = InetAddress.getByName(addr);
        MulticastSocket s = new MulticastSocket(port);
        s.setSoTimeout(WAIT_FOR_WAVE_SO_TIMEOUT);
        int bufferSize = WAIT_FOR_WAVE_BUFFER_SIZE;
        byte[] arb = new byte[bufferSize];
        s.joinGroup(group);
        String res;
        while (true) {
            DatagramPacket datagramPacket = new DatagramPacket(arb, bufferSize);
            try {
                s.receive(datagramPacket);

                // System.out.println(arb.length);
                res = new String(arb);
                System.out.println("got client wave: " + res);
                if (res.startsWith("hi_i_am_client")) {
                    String sendMessage = "hi_i_am_server";
                    sendMulticast(sendMessage, MULTI_CAST_ADDR, MCAST_CLIENT_PORT);
                    System.out.println("waitForClientWave, got client wave, waved back");
                    s.close();
                    return;
                }
            } catch (SocketTimeoutException e) {
                System.out.println("waiting for client wave timed out");
                //TODO: handle timeout: notify the user to start a new round of waiting
                break;
            } finally {
                try {
                    s.close();
                } catch (Exception ignored) {
                }
            }

        }
    }

    private void sendMulticast(String sendMessage, String addr, int port) throws IOException {
        InetAddress inetAddress = InetAddress.getByName(addr);
        DatagramPacket datagramPacket = new DatagramPacket(sendMessage.getBytes(), sendMessage.length(), inetAddress,
                port);
        MulticastSocket multicastSocket = new MulticastSocket();
        multicastSocket.send(datagramPacket);
        multicastSocket.close();
    }

    public static void main(String[] args) {
        // int port = Integer.parseInt(args[0]);
        try {
            Thread t = new MobileControlSever(Mode.DISCOVERY, LOCAL_TCP_PORT);
            t.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
