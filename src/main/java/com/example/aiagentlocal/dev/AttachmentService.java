package com.example.aiagentlocal.dev;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipFile;

@Service
public class AttachmentService {
    public record Attachment(String id, String name, String context, String imageBase64) {}
    private static final long MAX_BYTES = 10 * 1024 * 1024;
    private final Map<String, Attachment> attachments = new ConcurrentHashMap<>();

    public Attachment add(MultipartFile file) throws Exception {
        if (file.isEmpty() || file.getSize() > MAX_BYTES) throw new IllegalArgumentException("Choose a file up to 10 MB");
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("attachment");
        byte[] bytes = file.getBytes();
        String ext = extension(name);
        String image = isImage(ext) ? Base64.getEncoder().encodeToString(bytes) : null;
        String context = image == null ? extract(name, bytes) : "Image attachment: " + name;
        Attachment result = new Attachment(UUID.randomUUID().toString(), name, limit(context, 20_000), image);
        attachments.put(result.id(), result);
        return result;
    }
    public List<Attachment> find(List<String> ids) {
        if (ids == null) return List.of();
        return ids.stream().map(attachments::get).filter(Objects::nonNull).toList();
    }
    private String extract(String name, byte[] data) throws Exception {
        String ext = extension(name);
        if (Set.of("txt", "md", "csv", "json", "xml", "html", "java", "js", "py", "css", "sql", "yml", "yaml").contains(ext))
            return new String(data, StandardCharsets.UTF_8);
        if (ext.equals("docx")) return zipXml(data, List.of("word/document.xml"));
        if (ext.equals("xlsx")) return zipXml(data, List.of("xl/sharedStrings.xml", "xl/worksheets/sheet1.xml"));
        throw new IllegalArgumentException("Supported attachments: images, TXT, CSV, JSON, DOCX, XLSX and source files");
    }
    private String zipXml(byte[] data, List<String> entries) throws Exception {
        File temp = File.createTempFile("local-agent-attachment-", ".zip");
        try {
            java.nio.file.Files.write(temp.toPath(), data);
            StringBuilder out = new StringBuilder();
            try (ZipFile zip = new ZipFile(temp)) {
                for (String entry : entries) {
                    var item = zip.getEntry(entry);
                    if (item == null) continue;
                    try (InputStream in = zip.getInputStream(item)) {
                        String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                        out.append(xml.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ")).append('\n');
                    }
                }
            }
            return out.length() == 0 ? "No readable text found in document" : out.toString();
        } finally { temp.delete(); }
    }
    private static boolean isImage(String ext) { return Set.of("png", "jpg", "jpeg", "webp", "gif").contains(ext); }
    private static String extension(String name) { int i = name.lastIndexOf('.'); return i < 0 ? "" : name.substring(i + 1).toLowerCase(Locale.ROOT); }
    private static String limit(String text, int max) { return text.length() > max ? text.substring(0, max) + "\n[TRUNCATED]" : text; }
}
