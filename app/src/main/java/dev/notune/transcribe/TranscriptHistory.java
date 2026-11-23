package dev.notune.transcribe;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** The latest distinct transcripts, in insertion order from newest to oldest. */
public final class TranscriptHistory {
    public static final String FILE_NAME = "transcript_history.txt";
    // Keep in sync with last_recording::FILE_NAME in Rust.
    public static final String LAST_RECORDING_FILE = "last_recording.wav";
    public static final int MAX_ENTRIES = 5;

    public static final class Entry {
        public final String text;
        public final long timeMillis;

        private Entry(String text, long timeMillis) {
            this.text = text;
            this.timeMillis = timeMillis;
        }
    }

    private final File file;

    public TranscriptHistory(File file) {
        this.file = file;
    }

    public List<Entry> load() {
        List<Entry> entries = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while (entries.size() < MAX_ENTRIES && (line = reader.readLine()) != null) {
                Entry entry = parse(line);
                if (entry != null) entries.add(entry);
            }
        } catch (IOException ignored) {
            // Missing files and unreadable lines leave any readable entries intact.
        }
        return entries;
    }

    public void add(String text, long timeMillis) throws IOException {
        if (text == null || text.trim().isEmpty()) return;
        String trimmed = text.trim();
        List<Entry> entries = load();
        entries.removeIf(entry -> entry.text.equals(trimmed));
        entries.add(0, new Entry(trimmed, timeMillis));

        File temp = new File(file.getPath() + ".tmp");
        try {
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(temp), StandardCharsets.UTF_8))) {
                for (int i = 0; i < Math.min(entries.size(), MAX_ENTRIES); i++) {
                    Entry entry = entries.get(i);
                    writer.write(Long.toString(entry.timeMillis));
                    writer.write('\t');
                    writer.write(escape(entry.text));
                    writer.newLine();
                }
            }
            if (!temp.renameTo(file)) {
                throw new IOException("Could not replace " + file.getName());
            }
        } finally {
            temp.delete();
        }
    }

    public void clear() {
        file.delete();
    }

    private static Entry parse(String line) {
        int separator = line.indexOf('\t');
        if (separator <= 0 || line.indexOf('\t', separator + 1) != -1) return null;
        try {
            long timeMillis = Long.parseLong(line.substring(0, separator));
            String text = unescape(line.substring(separator + 1));
            return text == null || text.trim().isEmpty() ? null : new Entry(text, timeMillis);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String unescape(String text) {
        StringBuilder result = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch != '\\') {
                result.append(ch);
                continue;
            }
            if (++i == text.length()) return null;
            switch (text.charAt(i)) {
                case '\\': result.append('\\'); break;
                case 'n': result.append('\n'); break;
                case 'r': result.append('\r'); break;
                case 't': result.append('\t'); break;
                default: return null;
            }
        }
        return result.toString();
    }
}
