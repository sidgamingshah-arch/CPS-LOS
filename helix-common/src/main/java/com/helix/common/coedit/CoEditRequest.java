package com.helix.common.coedit;

/**
 * A request to publish one artifact for co-editing. The caller (frontend / owning
 * service / e2e) supplies the artifact bytes it already rendered locally — helix-common
 * stays decoupled from any one service's document routes. {@code localUrl} is an optional
 * pointer back to the existing local export endpoint (echoed into the result so the UI can
 * still offer the plain download).
 *
 * @param subjectType audit subject type (e.g. {@code GeneratedDocument}, {@code Application})
 * @param subjectId   audit subject id (e.g. the document id, the application reference)
 * @param fileName    artifact file name incl. extension (drives the SharePoint item name)
 * @param contentType MIME type of the artifact (e.g. {@code text/html}, {@code text/csv})
 * @param content     the artifact bytes, verbatim
 * @param localUrl    optional link to the existing local export (never fetched here)
 * @param actor       the X-Actor performing the action (audit accountability)
 */
public record CoEditRequest(String subjectType, String subjectId, String fileName,
                            String contentType, byte[] content, String localUrl, String actor) {
}
