package com.odel.content.service;

import com.odel.common.ApiException;
import com.odel.content.entity.Item;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Which kind of item an uploaded file becomes, and how big it may be. */
public final class FileKinds {
    public static final long MB = 1024L * 1024;
    public static final long VIDEO_MAX = 2048 * MB;
    static final Set<String> VIDEO = Set.of("mp4", "mov", "m4v", "webm", "mkv", "avi");
    static final Set<String> AUDIO = Set.of("mp3", "m4a", "aac", "ogg", "wav");
    static final Set<String> SLIDES = Set.of("pptx", "ppt", "odp");
    static final Set<String> NOTES = Set.of("docx", "doc", "odt", "rtf");
    static final Map<Item.Kind, Long> MAX = Map.of(Item.Kind.VIDEO, VIDEO_MAX, Item.Kind.AUDIO, 200 * MB, Item.Kind.PDF, 50 * MB,
            Item.Kind.SLIDES, 100 * MB, Item.Kind.NOTES, 50 * MB);
    static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("mp4", "video/mp4"), Map.entry("mov", "video/quicktime"), Map.entry("m4v", "video/x-m4v"), Map.entry("webm", "video/webm"),
            Map.entry("mkv", "video/x-matroska"), Map.entry("avi", "video/x-msvideo"), Map.entry("mp3", "audio/mpeg"), Map.entry("m4a", "audio/mp4"),
            Map.entry("aac", "audio/aac"), Map.entry("ogg", "audio/ogg"), Map.entry("wav", "audio/wav"), Map.entry("pdf", "application/pdf"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"), Map.entry("ppt", "application/vnd.ms-powerpoint"),
            Map.entry("odp", "application/vnd.oasis.opendocument.presentation"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"), Map.entry("doc", "application/msword"),
            Map.entry("odt", "application/vnd.oasis.opendocument.text"), Map.entry("rtf", "application/rtf"));

    private FileKinds() {}

    public static String extension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT).trim();
    }

    /** The kind for a file name, or a 400 that says what can be uploaded. */
    public static Item.Kind kindOf(String fileName) {
        String ext = extension(fileName);
        if (VIDEO.contains(ext)) return Item.Kind.VIDEO;
        if (AUDIO.contains(ext)) return Item.Kind.AUDIO;
        if (ext.equals("pdf")) return Item.Kind.PDF;
        if (SLIDES.contains(ext)) return Item.Kind.SLIDES;
        if (NOTES.contains(ext)) return Item.Kind.NOTES;
        if (ext.equals("key")) throw ApiException.badRequest("unsupported_file", "Export Keynote files to PowerPoint or PDF first.");
        String shown = ext.isEmpty() ? "files without an extension" : "." + ext + " files";
        throw ApiException.badRequest("unsupported_file", "We cannot use " + shown + ". Upload a video, audio, PDF, PowerPoint or Word file.");
    }

    public static void checkSize(Item.Kind kind, long size) {
        if (size <= 0) throw ApiException.badRequest("empty_file", "That file is empty. Choose the file again.");
        long max = MAX.get(kind);
        if (size <= max) return;
        String limit = Labels.size(max);
        String message = switch (kind) {
            case VIDEO -> "Videos can be up to 2 GB. Shorten the video or export it at a lower quality, then try again.";
            case AUDIO -> "Audio files can be up to " + limit + ". Split the recording into parts, then try again.";
            case SLIDES -> "Slides can be up to " + limit + ". Compress the pictures in PowerPoint (File › Compress pictures), then try again.";
            default -> "Documents can be up to " + limit + ". Save a smaller copy (compress the pictures), then try again.";
        };
        throw ApiException.badRequest("file_too_large", message);
    }

    public static String contentType(String fileName, String given) {
        String t = TYPES.get(extension(fileName));
        if (t != null) return t;
        return given == null || given.isBlank() ? "application/octet-stream" : given;
    }

    /** "PowerPoint", "Word", "PDF"… for the conversion label. */
    public static String formatName(String fileName) {
        return switch (extension(fileName)) {
            case "pptx", "ppt" -> "PowerPoint";
            case "odp" -> "Slides";
            case "docx", "doc" -> "Word";
            case "odt" -> "Document";
            case "rtf" -> "Rich text";
            default -> extension(fileName).toUpperCase(Locale.ROOT);
        };
    }
}
