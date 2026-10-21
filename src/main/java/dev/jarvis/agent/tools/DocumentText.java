package dev.jarvis.agent.tools;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Plain text of a document: text files, PDF and Word (.docx). Pure logic, no Spring dependency.
 */
public final class DocumentText {

    public static final long MAX_BYTES = 25L * 1024 * 1024;
    private static final Set<String> TEXT = Set.of("txt", "md", "markdown", "csv", "tsv", "json", "xml", "html",
            "htm", "yml", "yaml", "properties", "ini", "toml", "log", "sql", "rst", "java", "kt", "py", "js",
            "ts", "go", "rs", "cs", "sh", "ps1");

    private DocumentText() {
    }

    public static boolean supported(Path file) {
        String extension = extension(file);
        return TEXT.contains(extension) || extension.equals("pdf") || extension.equals("docx");
    }

    /** @throws IOException if the file cannot be read or is not a valid document */
    public static String extract(Path file) throws IOException {
        if (Files.size(file) > MAX_BYTES) {
            throw new IOException("File troppo grande (oltre " + MAX_BYTES / (1024 * 1024) + " MB)");
        }
        return switch (extension(file)) {
            case "pdf" -> pdf(file);
            case "docx" -> docx(file);
            default -> text(file);
        };
    }

    private static String text(Path file) throws IOException {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (CharacterCodingException e) {
            return Files.readString(file, StandardCharsets.ISO_8859_1); // older Windows files
        }
    }

    private static String pdf(Path file) throws IOException {
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            return new PDFTextStripper().getText(document);
        }
    }

    /** The text runs of word/document.xml, one line per paragraph. */
    private static String docx(Path file) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry entry = zip.getEntry("word/document.xml");
            if (entry == null) {
                throw new IOException("Non è un documento Word valido");
            }
            try (InputStream xml = zip.getInputStream(entry)) {
                return wordText(xml);
            }
        }
    }

    private static String wordText(InputStream xml) throws IOException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        // Untrusted file: no DTD, no external entities (XXE)
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        StringBuilder text = new StringBuilder();
        try {
            XMLStreamReader reader = factory.createXMLStreamReader(xml);
            boolean inText = false;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamReader.START_ELEMENT) {
                    switch (reader.getLocalName()) {
                        case "t" -> inText = true;
                        case "tab" -> text.append('\t');
                        case "br" -> text.append('\n');
                        default -> { }
                    }
                } else if (event == XMLStreamReader.END_ELEMENT) {
                    if (reader.getLocalName().equals("t")) {
                        inText = false;
                    } else if (reader.getLocalName().equals("p")) {
                        text.append('\n');
                    }
                } else if (event == XMLStreamReader.CHARACTERS && inText) {
                    text.append(reader.getText());
                }
            }
        } catch (XMLStreamException e) {
            throw new IOException("Documento Word non leggibile: " + e.getMessage(), e);
        }
        return text.toString();
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
