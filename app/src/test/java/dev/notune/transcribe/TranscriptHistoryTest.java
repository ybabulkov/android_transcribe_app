package dev.notune.transcribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TranscriptHistoryTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private File file;
    private TranscriptHistory history;

    @Before
    public void setUp() {
        file = new File(folder.getRoot(), TranscriptHistory.FILE_NAME);
        history = new TranscriptHistory(file);
    }

    @Test
    public void empty_whenNoFile() {
        assertTrue(history.load().isEmpty());
    }

    @Test
    public void add_newestFirst() throws IOException {
        history.add("a", 1);
        history.add("b", 2);
        assertEquals(Arrays.asList("b", "a"), texts());
        assertEquals(Arrays.asList(2L, 1L), history.load().stream()
                .map(entry -> entry.timeMillis).collect(Collectors.toList()));
    }

    @Test
    public void cap_keepsFiveNewest() throws IOException {
        for (int i = 1; i <= 7; i++) {
            history.add(Integer.toString(i), i);
        }
        assertEquals(Arrays.asList("7", "6", "5", "4", "3"), texts());
    }

    @Test
    public void ignoresBlank() throws IOException {
        history.add(null, 1);
        history.add("", 2);
        history.add("  \n", 3);
        assertTrue(history.load().isEmpty());
        history.add("  hi  ", 4);
        assertEquals(Arrays.asList("hi"), texts());
    }

    @Test
    public void duplicate_movesToTop() throws IOException {
        history.add("a", 1);
        history.add("b", 2);
        history.add("a", 3);
        assertEquals(Arrays.asList("a", "b"), texts());
        assertEquals(3L, history.load().get(0).timeMillis);
    }

    @Test
    public void specialCharacters_roundTrip() throws IOException {
        String text = "a\nb\tc\\d\re Български";
        history.add(text, 1);
        assertEquals(Arrays.asList(text), texts());
    }

    @Test
    public void persistsAcrossInstances() throws IOException {
        history.add("persisted", 1);
        assertEquals("persisted", new TranscriptHistory(file).load().get(0).text);
    }

    @Test
    public void skipsMalformedLines() throws IOException {
        Files.write(file.toPath(), "garbage\n1\tbad\\x\nno-time\tbad\n5\tok\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(Arrays.asList("ok"), texts());
        assertEquals(5L, history.load().get(0).timeMillis);
    }

    @Test
    public void clear_removesAll() throws IOException {
        history.add("a", 1);
        history.clear();
        assertTrue(history.load().isEmpty());
        assertFalse(file.exists());
    }

    private List<String> texts() {
        return history.load().stream().map(entry -> entry.text).collect(Collectors.toList());
    }
}
