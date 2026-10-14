package it.belloworld.mercurygram.ui;

import static org.junit.Assert.*;
import android.app.Application;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 30)
public class MediaAndPhoneRegressionTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private String normalize(String phone) throws Exception {
        Method m=PhoneActionHelper.class.getDeclaredMethod("normalize",String.class);
        m.setAccessible(true); return (String)m.invoke(null,phone);
    }
    @Test public void phoneLinksDecodeAndKeepInternationalPrefix() throws Exception {
        assertEquals("+79991234567",normalize("tel:%2B7%20(999)%20123-45-67"));
        assertEquals("+79991234567",normalize(" +7 (999) 123-45-67 "));
        assertNull(normalize(null)); assertNull(normalize("nothing"));
    }
    @Test public void absentMediaReturnsNoMetadata() throws Exception {
        assertNull(Mp4MetadataReport.read(null).report);
        assertNull(Mp4MetadataReport.read(new File(temp.getRoot(),"missing.mp4")).creationDate);
    }
    private byte[] box(String type,byte[] payload)throws Exception{
        ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(b);
        out.writeInt(payload.length+8);out.writeBytes(type);out.write(payload);return b.toByteArray();
    }
    private File movie(long creationSeconds)throws Exception{
        File f=temp.newFile("movie-"+creationSeconds+".mp4");
        ByteBuffer mvhd=ByteBuffer.allocate(100);
        mvhd.putInt(0).putInt((int)creationSeconds).putInt((int)creationSeconds).putInt(1000).putInt(5000);
        try(FileOutputStream out=new FileOutputStream(f)){
            out.write(box("ftyp",new byte[]{'i','s','o','m',0,0,0,0,'i','s','o','m'}));
            out.write(box("moov",box("mvhd",mvhd.array())));
        }
        return f;
    }
    @Test public void readsMovieCreationTimestampLocally()throws Exception{
        long unix=1700000000L;
        Mp4MetadataReport.Result r=Mp4MetadataReport.read(movie(unix+2082844800L));
        assertNotNull(r.report);assertNotNull(r.report, r.creationDate);assertEquals(unix*1000,r.creationDate.getTime());
        assertTrue(r.report.contains("MP4"));
    }
    @Test public void zeroMovieTimestampIsNotARealRecordingDate()throws Exception{
        assertNull(Mp4MetadataReport.read(movie(0)).creationDate);
    }
}
