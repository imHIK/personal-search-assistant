/**
 * Personal Search Assistant — root package.
 *
 * <p>Architecture: ports &amp; adapters (hexagonal). The {@code domain} package is the core
 * and depends on nothing technical; it defines ports (interfaces) that adapters implement:
 * <ul>
 *   <li>inbound: {@code api} (REST)</li>
 *   <li>outbound: {@code storage.mongo}, {@code storage.search.opensearch}</li>
 *   <li>integration: {@code ingestion}, {@code indexing}, {@code retrieval}, {@code agent}</li>
 * </ul>
 * See ARCHITECTURE.md, docs/mongodb-schema.md and docs/opensearch-index.md.
 */
package io.personalassistant;
