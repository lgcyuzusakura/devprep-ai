package com.devprep.ai.service;

import com.devprep.ai.api.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

@Service
public class ResumeDocuments {
    private static final int MAX_FILE=2_000_000,MAX_TEXT=30_000;
    public String extract(String filename,String encoded) {
        if(filename==null||encoded==null||encoded.length()>2_666_672) throw invalid("文件最大为 2 MB。");
        try {
            byte[] bytes=Base64.getDecoder().decode(encoded);
            if(bytes.length==0||bytes.length>MAX_FILE) throw invalid("请选择 2 MB 以内的文件。");
            String lower=filename.toLowerCase(Locale.ROOT),text;
            if(lower.endsWith(".pdf")) {
                try(var pdf=Loader.loadPDF(bytes)) {
                    if(pdf.getNumberOfPages()>20)throw invalid("简历 PDF 最多支持20页。");
                    text=new PDFTextStripper().getText(pdf);
                }
            } else if(lower.endsWith(".docx")) {
                byte[] xml=null;int inflated=0,entries=0;
                try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
                    for(ZipEntry entry;(entry=zip.getNextEntry())!=null;) {
                        if(++entries>300)throw invalid("Word 文件内部条目过多。");
                        byte[] value=zip.readNBytes(MAX_FILE+1);inflated+=value.length;
                        if(inflated>8_000_000||value.length>MAX_FILE)throw invalid("Word 文件解压内容过大。");
                        if(entry.getName().equals("word/document.xml"))xml=value;
                    }
                }
                if(xml==null)throw invalid("没有找到 Word 正文，请使用 .docx 文件。");
                var factory=DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(true);factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
                var doc=factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
                StringBuilder content=new StringBuilder();
                var paragraphs=doc.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main","p");
                for(int i=0;i<paragraphs.getLength();i++) {
                    var texts=((Element)paragraphs.item(i)).getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main","t");
                    for(int j=0;j<texts.getLength();j++)content.append(texts.item(j).getTextContent());
                    content.append('\n');
                }
                text=content.toString();
            } else if(lower.endsWith(".txt")||lower.endsWith(".md")) {
                text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            } else throw invalid("支持 PDF、DOCX、UTF-8 TXT 和 Markdown 文件。");
            text=text.replace("\u0000","").strip();
            if(text.isEmpty())throw invalid("未提取到文字。扫描版 PDF 请先 OCR，或直接粘贴简历文本。");
            if(text.length()>MAX_TEXT)throw invalid("提取文字超过30,000字，请只保留简历正文。");
            return text;
        } catch(ApiException ex){throw ex;}catch(Exception ex){throw invalid("文件无法读取，请检查是否加密或损坏；也可直接粘贴文字。");}
    }
    public byte[] docx(String markdown) {
        return docx(markdown,JsonNodeFactory.instance.objectNode());
    }
    public byte[] docx(String markdown,JsonNode layout) {
        try {
            String template=layout.path("template").asText("classic");
            boolean serif=layout.path("font").asText().equals("serif");
            String font=serif?"Georgia":"Calibri",eastAsia=serif?"SimSun":"Microsoft YaHei";
            int size=Math.max(10,Math.min(15,layout.path("size").asInt(13)))*2;
            String density=layout.path("density").asText();
            int spacing=density.equals("compact")?40:density.equals("relaxed")?110:70;
            String color=switch(layout.path("accent").asText()){case "blue"->"245075";case "black"->"252525";default->"245F4B";};
            var output=new ByteArrayOutputStream();
            try(var zip=new ZipOutputStream(output,StandardCharsets.UTF_8)) {
                put(zip,"[Content_Types].xml","""
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>
                        """);
                put(zip,"_rels/.rels","""
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>
                        """);
                StringBuilder body=new StringBuilder();
                for(String raw:markdown.split("\\R")) {
                    if(raw.isBlank()) continue;
                    boolean title=raw.startsWith("# "),heading=raw.startsWith("##");
                    String line=raw.replaceFirst("^#{1,6}\\s+","").replaceFirst("^[-*]\\s+","• ")
                            .replaceAll("\\[([^]]+)]\\(([^)]+)\\)","$1 ($2)").replace("**","").replace("`","");
                    body.append("<w:p><w:pPr><w:spacing w:after=\"").append(heading?spacing+40:spacing).append("\" w:line=\"").append(density.equals("compact")?240:density.equals("relaxed")?310:270).append("\" w:lineRule=\"auto\"/>");
                    if(title||heading)body.append("<w:keepNext/>");
                    if(title&&template.equals("serif"))body.append("<w:jc w:val=\"center\"/>");
                    if(heading&&!template.equals("minimal"))body.append("<w:pBdr><w:bottom w:val=\"single\" w:sz=\"4\" w:color=\"").append(color).append("\"/></w:pBdr>");
                    body.append("</w:pPr><w:r><w:rPr><w:rFonts w:ascii=\"").append(font).append("\" w:hAnsi=\"").append(font).append("\" w:eastAsia=\"").append(eastAsia).append("\"/><w:sz w:val=\"")
                            .append(title?size+16:heading?size+2:size).append("\"/>");
                    if(title||heading)body.append("<w:color w:val=\"").append(color).append("\"/>");
                    if(title||heading)body.append("<w:b/>");
                    body.append("</w:rPr><w:t xml:space=\"preserve\">").append(xml(line)).append("</w:t></w:r></w:p>");
                }
                put(zip,"word/document.xml","<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"+body+
                        "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"850\" w:right=\"1000\" w:bottom=\"850\" w:left=\"1000\"/></w:sectPr></w:body></w:document>");
            }
            return output.toByteArray();
        } catch(IOException ex){throw new ApiException(500,"resume_export_failed","Word 导出失败，请使用 Markdown 导出。");}
    }
    private static String xml(String value){return value.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]","").replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");}
    private static void put(ZipOutputStream zip,String name,String value)throws IOException{zip.putNextEntry(new ZipEntry(name));zip.write(value.getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
    private static ApiException invalid(String message){return ApiException.badRequest("invalid_resume_file",message);}
}
