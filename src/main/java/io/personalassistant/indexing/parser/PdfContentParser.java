package io.personalassistant.indexing.parser;

import io.personalassistant.domain.model.ParsedContent;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.InputStream;
import org.apache.tika.parser.ParseContext;

@ApplicationScoped
public class PdfContentParser implements ContentParser {

    private final ParseContext context = TikaSupport.pdfContext();

    @Override
    public boolean supports(String contentType) {
        return "application/pdf".equals(TikaSupport.baseType(contentType));
    }

    @Override
    public ParsedContent parse(InputStream input, String contentType) {
        return TikaSupport.extract("pdf", input, contentType, context);
    }

    @Override
    public int priority() {
        return 10;
    }
}
