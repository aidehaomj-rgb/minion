import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Format/size conversion only, preserving the user-approved imagegen artwork and alpha. */
public final class BuildIconAssets {
    static void u16(OutputStream o,int n)throws IOException{o.write(n);o.write(n>>>8);}
    static void u32(OutputStream o,int n)throws IOException{u16(o,n);u16(o,n>>>16);}
    public static void main(String[] args)throws Exception{
        BufferedImage master=ImageIO.read(Paths.get(args[0]).toFile());
        if(master==null||!master.getColorModel().hasAlpha()||(master.getRGB(0,0)>>>24)!=0)throw new IOException("Transparent master required");
        Path dir=Paths.get(args[1]);Files.createDirectories(dir);
        int[] sizes={16,24,32,48,64,128,256};java.util.List<byte[]> frames=new ArrayList<byte[]>();
        for(int size:sizes){BufferedImage out=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);Graphics2D g=out.createGraphics();
            g.setComposite(AlphaComposite.Src);g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(master,0,0,size,size,null);g.dispose();ImageIO.write(out,"png",dir.resolve("minion-"+size+".png").toFile());
            ByteArrayOutputStream b=new ByteArrayOutputStream();int stride=((size+31)/32)*4;
            u32(b,40);u32(b,size);u32(b,size*2);u16(b,1);u16(b,32);u32(b,0);u32(b,size*size*4+stride*size);
            for(int i=0;i<4;i++)u32(b,0);
            for(int y=size-1;y>=0;y--)for(int x=0;x<size;x++){int c=out.getRGB(x,y);b.write(c);b.write(c>>>8);b.write(c>>>16);b.write(c>>>24);}
            for(int y=size-1;y>=0;y--){byte[] mask=new byte[stride];for(int x=0;x<size;x++)if((out.getRGB(x,y)>>>24)<128)mask[x/8]|=1<<(7-x%8);b.write(mask);}
            frames.add(b.toByteArray());
        }
        try(OutputStream ico=Files.newOutputStream(dir.resolve("minion.ico"))){u16(ico,0);u16(ico,1);u16(ico,sizes.length);int offset=6+16*sizes.length;
            for(int i=0;i<sizes.length;i++){int size=sizes[i];ico.write(size==256?0:size);ico.write(size==256?0:size);ico.write(0);ico.write(0);u16(ico,1);u16(ico,32);u32(ico,frames.get(i).length);u32(ico,offset);offset+=frames.get(i).length;}
            for(byte[] frame:frames)ico.write(frame);
        }
        System.out.println("PASS transparent PNG/ICO sizes: "+Arrays.toString(sizes));
    }
}
