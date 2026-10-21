package dev.jarvis.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentTextTest {

    @TempDir
    Path tmp;

    private Path docx(String name, String documentXml) throws Exception {
        Path file = tmp.resolve(name);
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(documentXml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return file;
    }

    @Test
    void readsTextFiles() throws Exception {
        Path notes = Files.writeString(tmp.resolve("note.md"), "# Riunione\nBudget approvato");

        assertThat(DocumentText.supported(notes)).isTrue();
        assertThat(DocumentText.extract(notes)).contains("Budget approvato");
        assertThat(DocumentText.supported(tmp.resolve("app.exe"))).isFalse();
    }

    @Test
    void readsWordParagraphs() throws Exception {
        Path file = docx("offerta.docx", """
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
                <w:p><w:r><w:t>Offerta</w:t></w:r><w:r><w:t xml:space="preserve"> commerciale</w:t></w:r></w:p>
                <w:p><w:r><w:t>Totale 1200 euro</w:t></w:r></w:p></w:body></w:document>""");

        assertThat(DocumentText.extract(file)).isEqualTo("Offerta commerciale\nTotale 1200 euro\n");
    }

    @Test
    void wordFilesCannotPullInExternalEntities() throws Exception {
        Path secret = Files.writeString(tmp.resolve("secret.txt"), "PASSWORD-123");
        Path file = docx("evil.docx", """
                <?xml version="1.0"?><!DOCTYPE d [<!ENTITY x SYSTEM "%s">]>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                <w:body><w:p><w:r><w:t>&x;</w:t></w:r></w:p></w:body></w:document>""".formatted(secret.toUri()));

        String text;
        try {
            text = DocumentText.extract(file);
        } catch (java.io.IOException rejected) {
            text = rejected.getMessage();
        }
        assertThat(text).doesNotContain("PASSWORD-123");
    }

    @Test
    void readsPdfText() throws Exception {
        Path file = tmp.resolve("fattura.pdf");
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 700);
                content.showText("Fattura numero 42");
                content.endText();
            }
            pdf.save(file.toFile());
        }

        assertThat(DocumentText.extract(file)).contains("Fattura numero 42");
    }
}
