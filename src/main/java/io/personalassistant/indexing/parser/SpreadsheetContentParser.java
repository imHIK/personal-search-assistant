package io.personalassistant.indexing.parser;

import io.personalassistant.domain.model.ParsedContent;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.InputStream;
import java.util.Set;
import org.apache.tika.parser.ParseContext;

/**
 * Dedicated spreadsheet extractor for Excel ({@code .xls}/{@code .xlsx}) and ODF spreadsheets. POI
 * (via Tika) emits each sheet's cells as text, row by row, with the sheet name as a heading — enough
 * for keyword/semantic recall over tabular data. (Plain {@code .csv}/{@code .tsv} stay on the
 * text parser, which already reads them verbatim.) Preferred over the generic fallback for Excel.
 */
@ApplicationScoped
public class SpreadsheetContentParser implements ContentParser {

    private static final Set<String> TYPES = Set.of(
            "application/vnd.ms-excel",                                                    // .xls
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",           // .xlsx
            "application/vnd.oasis.opendocument.spreadsheet");                             // .ods

    private final ParseContext context = TikaSupport.officeContext();

    @Override
    public boolean supports(String contentType) {
        return TYPES.contains(TikaSupport.baseType(contentType));
    }

    @Override
    public ParsedContent parse(InputStream input, String contentType) {
        return TikaSupport.extract("spreadsheet", input, contentType, context);
    }

    @Override
    public int priority() {
        return 10;
    }
}
