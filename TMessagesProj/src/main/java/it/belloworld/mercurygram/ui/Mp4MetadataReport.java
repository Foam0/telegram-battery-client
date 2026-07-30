package it.belloworld.mercurygram.ui;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import com.drew.metadata.mov.QuickTimeDirectory;
import com.drew.metadata.mov.metadata.QuickTimeMetadataDirectory;

import org.telegram.messenger.FileLog;

import java.io.File;
import java.util.Date;
import java.util.Locale;

/** Builds a complete, local, ExifTool-style tag listing for an MP4 file. */
final class Mp4MetadataReport {

    static final class Result {
        final String report;
        final Date creationDate;

        Result(String report, Date creationDate) {
            this.report = report;
            this.creationDate = creationDate;
        }
    }

    private Mp4MetadataReport() {
    }

    static Result read(File file) {
        if (file == null || !file.isFile()) {
            return new Result(null, null);
        }
        StringBuilder output = new StringBuilder(4096);
        output.append("Metadata Engine             : metadata-extractor 2.20.0\n");
        output.append("File Name                   : ").append(file.getName()).append('\n');
        output.append("Directory                   : ").append(file.getParent()).append('\n');
        output.append("File Size                   : ").append(file.length()).append(" bytes\n");
        output.append("File Type                   : MP4/QuickTime\n");

        try {
            Metadata metadata = ImageMetadataReader.readMetadata(file);
            Date creationDate = findCreationDate(metadata);
            for (Directory directory : metadata.getDirectories()) {
                output.append('\n').append("---- ").append(directory.getName()).append(" ----\n");
                for (Tag tag : directory.getTags()) {
                    appendTag(output, tag.getTagName(), tag.getDescription());
                }
                for (String error : directory.getErrors()) {
                    appendTag(output, "Error", error);
                }
            }
            return new Result(output.toString().trim(), creationDate);
        } catch (Throwable e) {
            FileLog.e(e);
            output.append('\n').append("Metadata Error              : ").append(e.getClass().getSimpleName());
            if (e.getMessage() != null) {
                output.append(": ").append(e.getMessage());
            }
            return new Result(output.toString().trim(), null);
        }
    }

    private static Date findCreationDate(Metadata metadata) {
        QuickTimeDirectory movie = metadata.getFirstDirectoryOfType(QuickTimeDirectory.class);
        Date date = movie == null ? null : movie.getDate(QuickTimeDirectory.TAG_CREATION_TIME);
        if (isMeaningful(date)) {
            return date;
        }
        QuickTimeMetadataDirectory tags = metadata.getFirstDirectoryOfType(QuickTimeMetadataDirectory.class);
        date = tags == null ? null : tags.getDate(QuickTimeMetadataDirectory.TAG_CREATION_DATE);
        return isMeaningful(date) ? date : null;
    }

    private static boolean isMeaningful(Date date) {
        // A zero value in an MP4 header maps to 1904 and means “not specified”.
        return date != null && date.getTime() >= 0;
    }

    private static void appendTag(StringBuilder output, String name, String value) {
        if (name == null) {
            name = "Unknown";
        }
        if (value == null) {
            value = "";
        }
        output.append(String.format(Locale.US, "%-27s : %s%n", name, value));
    }
}
