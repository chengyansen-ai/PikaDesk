package com.sojourners.chess.manual;

import com.sojourners.chess.model.ManualRecord;
import com.sojourners.chess.util.StringUtils;

import java.io.*;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * XQF (象棋演播室) 棋谱文件解析实现类
 * 参考 XQF 1.0 格式规范实现
 */
public class XqfChessManualImpl implements ChessManualService {

    // 32个棋子的FEN表示顺序: 红方车马相士帅士相马车炮炮兵兵兵兵兵 + 黑方车马象士将士象马车炮炮卒卒卒卒卒
    private static final String FEN_PIECES = "RNBAKABNRCCPPPPPrnbakabnrccppppp";

    // GBK编码用于解析中文文本
    private static final Charset GBK_CHARSET = Charset.forName("GBK");

    @Override
    public ChessManual openChessManual(File file) {
        try (FileInputStream fis = new FileInputStream(file);
             BufferedInputStream bis = new BufferedInputStream(fis)) {

            byte[] buffer = new byte[(int) file.length()];
            int read = bis.read(buffer);
            if (read != buffer.length) {
                throw new IOException("Failed to read complete file");
            }

            return parseXqfFile(buffer);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 解析XQF文件内容
     */
    private ChessManual parseXqfFile(byte[] buffer) {
        // 验证文件头标记 'XQ'
        if (buffer.length < 2 || buffer[0] != 'X' || buffer[1] != 'Q') {
            throw new IllegalArgumentException("Invalid XQF file header");
        }

        ChessManual cm = new ChessManual();

        // 解析文件头
        XqfHeader header = parseHeader(buffer);

        // 设置棋局基本信息
        cm.setName(header.title);
        cm.setDate(header.matchTime);
        cm.setCity(header.matchAddr);
        cm.setRed(header.redPlayer);
        cm.setBlack(header.blkPlayer);

        // 计算密钥
        XqfKey key = calculateKey(header);

        // 计算开局FEN串
        String fen = calculateFen(header, key);
        cm.setFenCode(fen);

        // 解析棋谱记录
        ManualRecord head = parseMoves(buffer, header, key);
        cm.setHead(head);

        // 翻译招法为中文
        translate(fen, head);

        return cm;
    }

    /**
     * XQF文件头结构
     */
    private static class XqfHeader {
        int version;        // 版本号
        int keyMask;        // 加密掩码
        byte[] keyOr;       // Or密钥 (4字节)
        int keySum;         // 加密的钥匙和
        int keyXYp;         // 棋子布局位置钥匙
        int keyXYf;         // 棋谱起点钥匙
        int keyXYt;         // 棋谱终点钥匙
        byte[] qiziXY;      // 32个棋子的原始位置 (32字节)
        int playStepNo;     // 棋谱文件的开始步数
        int whoPlay;        // 该谁下 (0=红方, 1=黑方)
        int playResult;     // 最终结果 (0=未知, 1=红胜, 2=黑胜, 3=和棋)
        int type;           // 对局类型
        String title;       // 标题
        String matchName;   // 比赛名称
        String matchTime;   // 比赛时间
        String matchAddr;   // 比赛地点
        String redPlayer;   // 红方姓名
        String blkPlayer;   // 黑方姓名
        String timeRule;    // 用时规则
        String redTime;     // 红方用时
        String blkTime;     // 黑方用时
        String rmkWriter;   // 棋谱评论员
        String author;      // 文件作者
    }

    /**
     * XQF密钥结构
     */
    private static class XqfKey {
        int[] f32 = new int[32];  // 32字节密钥数组
        int xYp;                  // 棋子位置密钥
        int xYf;                  // 起点密钥
        int xYt;                  // 终点密钥
        int rmk;                  // 注释长度密钥
    }

    /**
     * 解析文件头
     */
    private XqfHeader parseHeader(byte[] buffer) {
        XqfHeader header = new XqfHeader();

        header.version = buffer[2] & 0xFF;
        header.keyMask = buffer[3] & 0xFF;
        header.keyOr = new byte[]{buffer[8], buffer[9], buffer[10], buffer[11]};
        header.keySum = buffer[12] & 0xFF;
        header.keyXYp = buffer[13] & 0xFF;
        header.keyXYf = buffer[14] & 0xFF;
        header.keyXYt = buffer[15] & 0xFF;

        // 32个棋子位置
        header.qiziXY = new byte[32];
        System.arraycopy(buffer, 16, header.qiziXY, 0, 32);

        // 开始步数 (2字节, 小端序)
        header.playStepNo = (buffer[49] & 0xFF) * 256 + (buffer[48] & 0xFF);

        header.whoPlay = buffer[50] & 0xFF;
        header.playResult = buffer[51] & 0xFF;
        header.type = buffer[64] & 0xFF;

        // 解析字符串字段
        header.title = readString(buffer, 80, 128);
        header.matchName = readString(buffer, 208, 64);
        header.matchTime = readString(buffer, 272, 16);
        header.matchAddr = readString(buffer, 288, 16);
        header.redPlayer = readString(buffer, 304, 16);
        header.blkPlayer = readString(buffer, 320, 16);
        header.timeRule = readString(buffer, 336, 64);
        header.redTime = readString(buffer, 400, 16);
        header.blkTime = readString(buffer, 416, 16);
        header.rmkWriter = readString(buffer, 464, 16);
        header.author = readString(buffer, 480, 16);

        return header;
    }

    /**
     * 从指定位置读取字符串 (GBK编码)
     * 第一个字节是长度，后面是内容
     */
    private String readString(byte[] buffer, int offset, int maxLength) {
        int length = buffer[offset] & 0xFF;
        if (length == 0 || length > maxLength - 1) {
            return "";
        }

        // 找到实际内容长度(去除末尾的0)
        int actualLength = 0;
        for (int i = 0; i < length && i < maxLength - 1; i++) {
            if (buffer[offset + 1 + i] != 0) {
                actualLength++;
            } else {
                break;
            }
        }

        if (actualLength == 0) {
            return "";
        }

        byte[] strBytes = new byte[actualLength];
        System.arraycopy(buffer, offset + 1, strBytes, 0, actualLength);
        return new String(strBytes, GBK_CHARSET);
    }

    /**
     * 计算解密密钥
     */
    private XqfKey calculateKey(XqfHeader header) {
        XqfKey key = new XqfKey();

        // XQF 1.0 版本 (version <= 10) 没有加密
        if (header.version <= 10) {
            return key;
        }

        // 计算密钥
        key.xYp = ((header.keyXYp * header.keyXYp * 54 + 221) * header.keyXYp) & 0xFF;
        key.xYf = ((header.keyXYf * header.keyXYf * 54 + 221) * key.xYp) & 0xFF;
        key.xYt = ((header.keyXYt * header.keyXYt * 54 + 221) * key.xYf) & 0xFF;
        key.rmk = (((header.keySum * 256 + header.keyXYp) % 32000) + 767) & 0xFFFF;

        // 计算F32密钥数组
        int[] fKey = new int[]{
                (header.keySum & header.keyMask) | (header.keyOr[0] & 0xFF),
                (header.keyXYp & header.keyMask) | (header.keyOr[1] & 0xFF),
                (header.keyXYf & header.keyMask) | (header.keyOr[2] & 0xFF),
                (header.keyXYt & header.keyMask) | (header.keyOr[3] & 0xFF)
        };

        String seed = "[(C) Copyright Mr. Dong Shiwei.]";
        for (int i = 0; i < 32; i++) {
            key.f32[i] = fKey[i % 4] & seed.charAt(i);
        }

        return key;
    }

    /**
     * 计算开局FEN串
     */
    private String calculateFen(XqfHeader header, XqfKey key) {
        // 创建10x9的棋盘数组，初始填充为'*'
        char[] fenArray = new char[90];
        for (int i = 0; i < 90; i++) {
            fenArray[i] = '*';
        }

        // 放置32个棋子
        for (int i = 0; i < 32; i++) {
            int pieceKey;
            int piecePos;

            if (header.version > 10) {
                pieceKey = (key.xYp + i + 1) & 31;
                piecePos = ((header.qiziXY[i] & 0xFF) - key.xYp) & 0xFF;
            } else {
                pieceKey = i;
                piecePos = header.qiziXY[i] & 0xFF;
            }

            if (piecePos < 90) {
                // X坐标 = piecePos / 10, Y坐标 = piecePos % 10
                // FEN数组索引 = (9 - Y) * 9 + X
                int x = piecePos / 10;
                int y = 9 - (piecePos % 10);
                int index = y * 9 + x;
                if (index >= 0 && index < 90) {
                    fenArray[index] = FEN_PIECES.charAt(pieceKey);
                }
            }
        }

        // 转换为FEN格式
        StringBuilder fen = new StringBuilder();
        for (int row = 0; row < 10; row++) {
            int emptyCount = 0;
            for (int col = 0; col < 9; col++) {
                char c = fenArray[row * 9 + col];
                if (c == '*') {
                    emptyCount++;
                } else {
                    if (emptyCount > 0) {
                        fen.append(emptyCount);
                        emptyCount = 0;
                    }
                    fen.append(c);
                }
            }
            if (emptyCount > 0) {
                fen.append(emptyCount);
            }
            if (row < 9) {
                fen.append('/');
            }
        }

        // 添加轮次和其他信息
        fen.append(header.whoPlay == 1 ? " b - - 0 " : " w - - 0 ");
        fen.append(header.playStepNo > 0 ? (header.playStepNo >> 1) : 1);

        return fen.toString();
    }

    /**
     * 解析棋谱着法记录
     */
    private ManualRecord parseMoves(byte[] buffer, XqfHeader header, XqfKey key) {
        ManualRecord head = new ManualRecord(0, "开始局面", 0);

        // 棋谱记录从0x400 (1024)开始
        if (buffer.length <= 1024) {
            return head;
        }

        // 解密数据
        int dataLength = buffer.length - 1024;
        byte[] decode = new byte[dataLength];

        for (int i = 0; i < dataLength; i++) {
            if (header.version > 10) {
                decode[i] = (byte) (((buffer[1024 + i] & 0xFF) - key.f32[i % 32]) & 0xFF);
            } else {
                decode[i] = buffer[1024 + i];
            }
        }

        // 解析棋步
        List<ManualRecord> changeNodeStack = new ArrayList<>();
        ManualRecord parent = head;
        int pos = 0;
        int moveId = 0;

        while (pos < decode.length) {
            // 确保至少有8个字节
            if (pos + 8 > decode.length) {
                break;
            }

            int commentLen = 0;
            int nextOffset = 4;

            // 判断是否有后续着法和变着
            boolean hasNext;
            boolean hasChange;

            if (header.version > 10) {
                hasNext = (decode[pos + 2] & 0x80) != 0;
                hasChange = (decode[pos + 2] & 0x40) != 0;

                // 检查是否有注释
                if ((decode[pos + 2] & 0x20) != 0) {
                    commentLen = readInt32(decode, pos + 4) - key.rmk;
                    if (commentLen < 0) commentLen = 0;
                    nextOffset = commentLen + 8;
                }
            } else {
                hasNext = (decode[pos + 2] & 0xF0) != 0;
                hasChange = (decode[pos + 2] & 0x0F) != 0;
                commentLen = readInt32(decode, pos + 4);
                nextOffset = commentLen + 8;
            }

            // 读取注释
            String comment = "";
            if (commentLen > 0 && pos + 8 + commentLen <= decode.length) {
                byte[] commentBytes = new byte[commentLen];
                System.arraycopy(decode, pos + 8, commentBytes, 0, commentLen);
                comment = new String(commentBytes, GBK_CHARSET);
            }

            // 第0步是空着法，只包含注释
            if (pos == 0) {
                if (StringUtils.isNotEmpty(comment)) {
                    head.setRemark(comment);
                }
                pos += hasNext ? nextOffset : decode.length;
                continue;
            }

            // 解析着法坐标
            int pf = ((decode[pos] & 0xFF) - 24 - key.xYf) & 0xFF;
            int pt = ((decode[pos + 1] & 0xFF) - 32 - key.xYt) & 0xFF;

            // 转换为ICCS格式 (如 a0a1)
            String move = convertToIccs(pf, pt);

            // 创建新节点
            moveId++;
            ManualRecord step = new ManualRecord(moveId, move, "");
            if (StringUtils.isNotEmpty(comment)) {
                step.setRemark(comment);
            }

            parent.getList().add(step);

            if (hasNext) {
                if (hasChange) {
                    changeNodeStack.add(parent);
                }
                parent = step;
            } else {
                if (!hasChange) {
                    if (!changeNodeStack.isEmpty()) {
                        parent = changeNodeStack.remove(changeNodeStack.size() - 1);
                    }
                }
            }

            pos += nextOffset;

            // 安全检查：如果parent为null，退出循环
            if (parent == null) {
                break;
            }
        }

        return head;
    }

    /**
     * 读取32位整数 (小端序)
     */
    private int readInt32(byte[] data, int offset) {
        if (offset + 4 > data.length) {
            return 0;
        }
        return (data[offset] & 0xFF) |
                ((data[offset + 1] & 0xFF) << 8) |
                ((data[offset + 2] & 0xFF) << 16) |
                ((data[offset + 3] & 0xFF) << 24);
    }

    /**
     * 将XQF坐标转换为ICCS格式
     * XQF坐标: X * 10 + Y, 其中X(0-8), Y(0-9), 原点(0,0)在左下角
     * ICCS格式: 起始列(a-i) + 起始行(0-9) + 目标列(a-i) + 目标行(0-9)
     */
    private String convertToIccs(int fromPos, int toPos) {
        int fromX = fromPos / 10;
        int fromY = fromPos % 10;
        int toX = toPos / 10;
        int toY = toPos % 10;

        // 限制在有效范围内
        fromX = Math.max(0, Math.min(8, fromX));
        fromY = Math.max(0, Math.min(9, fromY));
        toX = Math.max(0, Math.min(8, toX));
        toY = Math.max(0, Math.min(9, toY));

        char fromFile = (char) ('a' + fromX);
        char toFile = (char) ('a' + toX);

        return "" + fromFile + fromY + toFile + toY;
    }

    @Override
    public void saveChessManual(ChessManual chessManual, File file) {
//        try (FileOutputStream fos = new FileOutputStream(file);
//             BufferedOutputStream bos = new BufferedOutputStream(fos)) {
//
//            byte[] buffer = createXqfFile(chessManual);
//            bos.write(buffer);
//
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
    }

    /**
     * 创建XQF文件内容
     */
    private byte[] createXqfFile(ChessManual cm) {
        // 计算文件大小 (头部1024字节 + 棋谱数据)
        int moveCount = countMoves(cm.getHead());
        int fileSize = 1024 + 8 + (moveCount * 8);  // 基础大小估算

        byte[] buffer = new byte[fileSize];

        // 文件标记 'XQ'
        buffer[0] = 'X';
        buffer[1] = 'Q';

        // 版本号 (使用1.0格式 = 10)
        buffer[2] = 10;

        // 填充棋局信息
        writeString(buffer, 80, cm.getName(), 128);
        writeString(buffer, 208, "", 64);  // 比赛名称
        writeString(buffer, 272, cm.getDate(), 16);
        writeString(buffer, 288, cm.getCity(), 16);
        writeString(buffer, 304, cm.getRed(), 16);
        writeString(buffer, 320, cm.getBlack(), 16);
        writeString(buffer, 336, "", 64);  // 用时规则
        writeString(buffer, 400, "", 16);  // 红方用时
        writeString(buffer, 416, "", 16);  // 黑方用时
        writeString(buffer, 464, "", 16);  // 评论员
        writeString(buffer, 480, "", 16);  // 作者

        // 设置结果
        buffer[51] = 0;  // 未知

        // 设置轮次
        String fen = cm.getFenCode();
        if (fen != null && fen.contains(" b ")) {
            buffer[50] = 1;  // 黑方走
        } else {
            buffer[50] = 0;  // 红方走
        }

        // 填充初始局面 (32个棋子位置)
        fillInitialPosition(buffer, fen);

        // 填充棋谱记录
        int pos = 1024;

        // 第0步 (空着法)
        buffer[pos++] = 0x18;
        buffer[pos++] = 0x20;
        buffer[pos++] = (byte) 0xF0;
        buffer[pos++] = (byte) 0xFF;
        buffer[pos++] = 0;
        buffer[pos++] = 0;
        buffer[pos++] = 0;
        buffer[pos++] = 0;

        // 写入着法
        pos = writeMoves(buffer, pos, cm.getHead());

        // 调整最后一步的标志
        if (pos > 1032) {
            buffer[pos - 8 + 2] = 0;  // 最后一步的第3字节设为0
        }

        // 截断到实际大小
        byte[] result = new byte[pos];
        System.arraycopy(buffer, 0, result, 0, pos);
        return result;
    }

    /**
     * 填充初始局面
     */
    private void fillInitialPosition(byte[] buffer, String fen) {
        // 标准初始局面位置 (如果FEN是初始局面)
        byte[] standardPos = new byte[]{
                (byte) 0x50, (byte) 0x46, (byte) 0x3C, (byte) 0x32, (byte) 0x28, (byte) 0x1E, (byte) 0x14, (byte) 0x0A,
                (byte) 0x00, (byte) 0x48, (byte) 0x0C, (byte) 0x53, (byte) 0x3F, (byte) 0x2B, (byte) 0x17, (byte) 0x03,
                (byte) 0x09, (byte) 0x13, (byte) 0x1D, (byte) 0x27, (byte) 0x31, (byte) 0x3B, (byte) 0x45, (byte) 0x4F,
                (byte) 0x59, (byte) 0x11, (byte) 0x4D, (byte) 0x06, (byte) 0x1A, (byte) 0x2E, (byte) 0x42, (byte) 0x56
        };

        System.arraycopy(standardPos, 0, buffer, 16, 32);
    }

    /**
     * 写入着法记录
     */
    private int writeMoves(byte[] buffer, int startPos, ManualRecord head) {
        int pos = startPos;

        for (ManualRecord record : head.getList()) {
            String move = record.getMove();
            if (StringUtils.isEmpty(move) || move.length() < 4) {
                continue;
            }

            // 解析ICCS格式 (如 a0a1)
            int fromX = move.charAt(0) - 'a';
            int fromY = move.charAt(1) - '0';
            int toX = move.charAt(2) - 'a';
            int toY = move.charAt(3) - '0';

            // 转换为XQF坐标格式
            buffer[pos++] = (byte) (fromX * 10 + fromY + 24);
            buffer[pos++] = (byte) (toX * 10 + toY + 32);

            // 标志字节 (有后续着法)
            boolean hasNext = !record.getList().isEmpty();
            buffer[pos++] = hasNext ? (byte) 0xF0 : 0;
            buffer[pos++] = 0;

            // 注释长度 (暂不支持)
            buffer[pos++] = 0;
            buffer[pos++] = 0;
            buffer[pos++] = 0;
            buffer[pos++] = 0;

            // 递归处理变着
            if (hasNext) {
                pos = writeMovesRecursive(buffer, pos, record);
            }
        }

        return pos;
    }

    /**
     * 递归写入着法记录
     */
    private int writeMovesRecursive(byte[] buffer, int pos, ManualRecord parent) {
        for (int i = 0; i < parent.getList().size(); i++) {
            ManualRecord record = parent.getList().get(i);
            String move = record.getMove();
            if (StringUtils.isEmpty(move) || move.length() < 4) {
                continue;
            }

            // 解析ICCS格式
            int fromX = move.charAt(0) - 'a';
            int fromY = move.charAt(1) - '0';
            int toX = move.charAt(2) - 'a';
            int toY = move.charAt(3) - '0';

            // 转换为XQF坐标格式
            buffer[pos++] = (byte) (fromX * 10 + fromY + 24);
            buffer[pos++] = (byte) (toX * 10 + toY + 32);

            // 标志字节
            boolean hasNext = !record.getList().isEmpty();
            boolean hasSibling = i < parent.getList().size() - 1;

            int flag = 0;
            if (hasNext) flag |= 0xF0;
            if (hasSibling) flag |= 0x0F;
            buffer[pos++] = (byte) flag;
            buffer[pos++] = 0;

            // 注释长度
            buffer[pos++] = 0;
            buffer[pos++] = 0;
            buffer[pos++] = 0;
            buffer[pos++] = 0;

            if (hasNext) {
                pos = writeMovesRecursive(buffer, pos, record);
            }
        }
        return pos;
    }

    /**
     * 写入字符串到缓冲区
     */
    private void writeString(byte[] buffer, int offset, String text, int maxLength) {
        if (text == null) {
            text = "";
        }

        byte[] bytes = text.getBytes(GBK_CHARSET);
        int length = Math.min(bytes.length, maxLength - 1);

        buffer[offset] = (byte) length;
        System.arraycopy(bytes, 0, buffer, offset + 1, length);
    }

    /**
     * 统计着法数量
     */
    private int countMoves(ManualRecord head) {
        int count = 0;
        for (ManualRecord record : head.getList()) {
            count += countMovesRecursive(record);
        }
        return count;
    }

    private int countMovesRecursive(ManualRecord record) {
        int count = 1;
        for (ManualRecord child : record.getList()) {
            count += countMovesRecursive(child);
        }
        return count;
    }
}
