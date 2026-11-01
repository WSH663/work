package cn.accessible.travel;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PromotionCodeImageTest {
    @Test void brandingPreservesEveryPixelOutsideTheOfficialCentreAvatarArea() throws Exception {
        int size=430;
        BufferedImage original=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<size;y++)for(int x=0;x<size;x++)original.setRGB(x,y,((x+y)%7<3?Color.BLACK:Color.WHITE).getRGB());
        var graphics=original.createGraphics();graphics.setColor(new Color(240,240,240));graphics.fillOval(120,120,190,190);graphics.dispose();
        var encoded=new ByteArrayOutputStream();ImageIO.write(original,"png",encoded);
        BufferedImage branded=ImageIO.read(new ByteArrayInputStream(PromotionService.brandCode(encoded.toByteArray())));
        assertThat(branded.getWidth()).isEqualTo(size);assertThat(branded.getHeight()).isEqualTo(size);
        // The renderer reserves a small rasterisation margin around the 40% logo
        // mask, still inside the official code's 44% centre avatar circle.
        var centre=new java.awt.geom.Ellipse2D.Double(size*0.29,size*0.29,size*0.42,size*0.42);
        int changed=0;
        for(int y=0;y<size;y++)for(int x=0;x<size;x++){
            if(!centre.contains(x+0.5,y+0.5))assertThat(branded.getRGB(x,y)).as("encoding pixel %s,%s",x,y).isEqualTo(original.getRGB(x,y));
            else if(branded.getRGB(x,y)!=original.getRGB(x,y))changed++;
        }
        assertThat(changed).isGreaterThan(1000);
    }
    @Test void malformedImagesAreNotRedrawn() {
        byte[] input={1,2,3};assertThat(PromotionService.brandCode(input)).isSameAs(input);
    }
}
