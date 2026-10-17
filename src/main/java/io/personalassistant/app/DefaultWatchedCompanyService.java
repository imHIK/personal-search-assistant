package io.personalassistant.app;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.common.JsonConfigLoader;
import io.personalassistant.common.id.Ids;
import io.personalassistant.domain.model.CompanyTier;
import io.personalassistant.domain.model.WatchedCompany;
import io.personalassistant.domain.service.WatchedCompanyService;
import io.personalassistant.storage.repository.WatchedCompanyRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;

@ApplicationScoped
public class DefaultWatchedCompanyService implements WatchedCompanyService {

    static final String BUNDLED = "config/watched-companies.json";

    private final WatchedCompanyRepository companies;

    Clock clock = Clock.systemUTC();

    @Inject
    public DefaultWatchedCompanyService(WatchedCompanyRepository companies) {
        this.companies = companies;
    }

    @Override
    public WatchedCompany create(CompanyFields fields) {
        String name = requireName(fields.name());
        String careersUrl = checkUrl(blankToNull(fields.careersUrl()));
        if (companies.findByName(name).isPresent()) {
            throw new IllegalStateException("A watched company named " + name + " already exists");
        }
        Instant now = clock.instant();
        return companies.insert(new WatchedCompany(Ids.watchedCompany(), name, blankToNull(fields.domain()),
                careersUrl, blankToNull(fields.platform()),
                fields.tier() == null ? CompanyTier.MEDIUM : fields.tier(), blankToNull(fields.reason()),
                blankToNull(fields.note()), blankToNull(fields.lookupHandle()), null, now, now));
    }

    @Override
    public Optional<WatchedCompany> get(String id) {
        return companies.findById(id);
    }

    @Override
    public List<WatchedCompany> list() {
        return companies.findAll();
    }

    @Override
    public WatchedCompany update(String id, CompanyFields edit) {
        WatchedCompany current = require(id);
        String name = edit.name() == null ? current.name() : requireName(edit.name());
        companies.findByName(name).filter(other -> !other.id().equals(id)).ifPresent(other -> {
            throw new IllegalStateException("A watched company named " + name + " already exists");
        });
        companies.updateEdits(id, name,
                merge(edit.domain(), current.domain()),
                checkUrl(merge(edit.careersUrl(), current.careersUrl())),
                merge(edit.platform(), current.platform()),
                edit.tier() == null ? current.tier() : edit.tier(),
                merge(edit.reason(), current.reason()),
                merge(edit.note(), current.note()),
                merge(edit.lookupHandle(), current.lookupHandle()),
                clock.instant());
        return require(id);
    }

    @Override
    public WatchedCompany markChecked(String id) {
        require(id);
        companies.updateLastCheckedAt(id, clock.instant());
        return require(id);
    }

    @Override
    public void delete(String id) {
        require(id);
        companies.delete(id);
    }

    @Override
    public ImportResult importBundled() {
        List<String> added = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> tiered = new ArrayList<>();
        for (CompanyFields fields : bundled()) {
            Optional<WatchedCompany> existing = companies.findByName(fields.name());
            if (existing.isPresent()) {
                skipped.add(fields.name());
                if (companies.fillTier(existing.get().id(), fields.tier())) {
                    tiered.add(fields.name());
                }
            } else {
                added.add(create(fields).name());
            }
        }
        return new ImportResult(added, skipped, tiered);
    }

    /** @throws IllegalStateException if the bundled file is missing or an entry has no name or tier */
    static List<CompanyFields> bundled() {
        JsonNode root = JsonConfigLoader.load(BUNDLED, Optional.empty());
        List<CompanyFields> out = new ArrayList<>();
        root.path("companies").forEach(node -> out.add(new CompanyFields(
                JsonConfigLoader.requiredText(node, "name", BUNDLED),
                text(node, "domain"), text(node, "careersUrl"), text(node, "platform"), tier(node),
                text(node, "reason"), null, text(node, "lookupHandle"))));
        return out;
    }

    private static CompanyTier tier(JsonNode node) {
        String value = JsonConfigLoader.requiredText(node, "tier", BUNDLED);
        try {
            return CompanyTier.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Unknown tier \"" + value + "\" in " + BUNDLED);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    private WatchedCompany require(String id) {
        return companies.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No watched company with id " + id));
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return name.trim().replaceAll("\\s+", " ");
    }

    /** The careers link is opened from the console, so anything but http(s) is refused. */
    private static String checkUrl(String url) {
        if (url == null) {
            return null;
        }
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null) {
                return url;
            }
        } catch (URISyntaxException e) {
            // Reported below.
        }
        throw new IllegalArgumentException("careersUrl must be an http(s) URL: " + url);
    }

    private static String merge(String edit, String current) {
        return edit == null ? current : blankToNull(edit);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
