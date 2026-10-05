package com.vay.camerasnap;

import java.nio.charset.StandardCharsets;

final class MediaSavePath {
    static final String DEFAULT = "DCIM/Camera/Snap";

    static String normalize(String input) {
        if (input == null) throw new IllegalArgumentException("请输入保存路径");
        String path = input.trim();
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        String[] parts = path.split("/", -1);
        if (!parts[0].equals("DCIM"))
            throw new IllegalArgumentException("请输入 DCIM 下的相对目录，例如 DCIM/Camera/Snap");
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.startsWith("."))
                throw new IllegalArgumentException("目录不能包含空层级、隐藏目录或 . 和 ..");
            if (!part.equals(part.trim()) || part.getBytes(StandardCharsets.UTF_8).length > 255)
                throw new IllegalArgumentException("目录名不能以空格开头或结尾，也不能过长");
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (Character.isISOControl(c) || "\\:*?\"<>|".indexOf(c) >= 0)
                    throw new IllegalArgumentException("目录包含不支持的字符");
            }
        }
        return path;
    }

    static String orDefault(String input) {
        try { return normalize(input); }
        catch (IllegalArgumentException e) { return DEFAULT; }
    }
}
