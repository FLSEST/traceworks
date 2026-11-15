package traceworks.core.view;

import traceworks.core.port.BlobRef;
import traceworks.core.port.BlobStore;

import java.nio.charset.StandardCharsets;

/** Writes the content of blobs into the page: escaped, cut at {@link #LIMIT} characters, and marked when deleted. */
final class Content {

    /** The most characters of one content block that the page holds. */
    static final int LIMIT = 65_536;

    private final BlobStore blobs;

    Content(BlobStore blobs) {
        this.blobs = blobs;
    }

    /** A collapsed block with a label; nothing when the reference is {@code null}. */
    void details(StringBuilder out, String label, BlobRef ref) {
        if (ref == null) {
            return;
        }
        out.append("<details><summary>").append(Html.line(label)).append("</summary>");
        pre(out, ref);
        out.append("</details>\n");
    }

    /** The content as {@code <pre>}, or a note that it is deleted, or the cut content with a note naming the blob. */
    void pre(StringBuilder out, BlobRef ref) {
        if (ref == null) {
            return;
        }
        var bytes = blobs.get(ref.sha256());
        if (bytes.isEmpty()) {
            out.append("<p class=\"deleted\">content deleted (sha256 ").append(Html.line(ref.sha256()))
                    .append(")</p>");
            return;
        }
        String text = new String(bytes.get(), StandardCharsets.UTF_8);
        String shown = Html.cut(text, LIMIT);
        out.append("<pre>").append(Html.text(shown)).append("</pre>");
        if (shown.length() < text.length()) {
            out.append("<p class=\"cut\">cut at ").append(shown.length()).append(" of ").append(text.length())
                    .append(" characters; the full content is blob ").append(Html.line(ref.sha256()))
                    .append("</p>");
        }
    }

    /** The content as it is, not escaped, for a caller that takes a part of it; {@code null} when there is none or it is deleted. */
    String text(BlobRef ref) {
        if (ref == null) {
            return null;
        }
        return blobs.get(ref.sha256()).map(bytes -> new String(bytes, StandardCharsets.UTF_8)).orElse(null);
    }

    /** The content as one escaped line for short values such as an answer; cut and marked like {@link #pre}. */
    String inline(BlobRef ref) {
        if (ref == null) {
            return "";
        }
        var bytes = blobs.get(ref.sha256());
        if (bytes.isEmpty()) {
            return "<em class=\"deleted\">content deleted (sha256 " + Html.line(ref.sha256()) + ")</em>";
        }
        String text = new String(bytes.get(), StandardCharsets.UTF_8);
        String shown = Html.cut(text, LIMIT);
        String note = shown.length() < text.length()
                ? " <em class=\"cut\">(cut at " + shown.length() + " of " + text.length()
                + " characters; the full content is blob " + Html.line(ref.sha256()) + ")</em>"
                : "";
        return "<code>" + Html.text(shown) + "</code>" + note;
    }
}
