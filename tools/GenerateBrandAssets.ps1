param([string]$StoreDirectory)
Add-Type -AssemblyName System.Drawing
Add-Type -ReferencedAssemblies System.Drawing -TypeDefinition @'
using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.IO;
public static class BuzinBrand {
  static Color C(string color) { return ColorTranslator.FromHtml(color); }
  static GraphicsPath Round(float x,float y,float w,float h,float r) {
    var p=new GraphicsPath(); float d=r*2;
    p.AddArc(x,y,d,d,180,90);p.AddArc(x+w-d,y,d,d,270,90);p.AddArc(x+w-d,y+h-d,d,d,0,90);p.AddArc(x,y+h-d,d,d,90,90);p.CloseFigure();return p;
  }
  static GraphicsPath Letter() {
    var p=new GraphicsPath(FillMode.Alternate);
    p.StartFigure();p.AddLine(29,18,54,18);p.AddBezier(54,18,79,18,83,43,67,50);p.AddBezier(67,50,88,59,77,84,54,84);p.AddLine(54,84,29,84);p.CloseFigure();
    p.StartFigure();p.AddLine(43,31,53,31);p.AddBezier(53,31,66,31,66,44,53,44);p.AddLine(53,44,43,44);p.CloseFigure();
    p.StartFigure();p.AddLine(43,57,55,57);p.AddBezier(55,57,69,57,69,71,55,71);p.AddLine(55,71,43,71);p.CloseFigure();return p;
  }
  static void Mark(Graphics g,float x,float y,float size,Color ink) {
    var state=g.Save();g.TranslateTransform(x,y);g.ScaleTransform(size/100,size/100);
    using(var p=Letter()) using(var b=new SolidBrush(ink)) g.FillPath(b,p);
    using(var p=new Pen(ink,3.4f)) {p.StartCap=LineCap.Round;p.EndCap=LineCap.Round;g.DrawArc(p,76,18,14,14,270,90);g.DrawArc(p,76,10,30,30,270,90);}
    g.Restore(state);
  }
  static void Text(Graphics g,string text,string family,float size,FontStyle style,Color color,float x,float y) {
    using(var f=new Font(family,size,style,GraphicsUnit.Pixel)) using(var b=new SolidBrush(color)) g.DrawString(text,f,b,x,y,StringFormat.GenericTypographic);
  }
  public static void GenerateStoreIcon(string path, int width, int height) {
    using(var bmp=new Bitmap(width,height, width==height ? PixelFormat.Format32bppArgb : PixelFormat.Format24bppRgb))
    using(var g=Graphics.FromImage(bmp)) {
      g.SmoothingMode=SmoothingMode.AntiAlias;
      g.TextRenderingHint=System.Drawing.Text.TextRenderingHint.AntiAliasGridFit;
      var frame=new Rectangle(0,0,width,height);
      if(width==height) {
        using(var mask=Round(0,0,width,height,width*0.075f)) g.SetClip(mask);
      }
      using(var bg=new LinearGradientBrush(frame,C("#12364A"),C("#071621"),38))
        g.FillRectangle(bg,frame);
      using(var orbit=new Pen(Color.FromArgb(52,53,192,216),width/640f)) {
        foreach(float radius in new[]{0.27f,0.38f,0.50f})
          g.DrawEllipse(orbit,width*(0.57f-radius),height*0.49f-width*radius,width*radius*2,width*radius*2);
      }
      float scale=width/600f;
      g.TranslateTransform(width/2f-230*scale,height/2f-88*scale);
      g.ScaleTransform(scale,scale);
      using(var tile=Round(0,0,176,176,40))
      using(var mint=new LinearGradientBrush(new Rectangle(0,0,176,176),C("#91FFD6"),C("#42DFAE"),45))
        g.FillPath(mint,tile);
      Mark(g,3,7,151,C("#092A24"));
      Text(g,"Buzin","Segoe UI",100,FontStyle.Bold,C("#F3F7F5"),202,11);
      Text(g,"CAST","Segoe UI",55,FontStyle.Bold,C("#7CF4CD"),207,111);
      bmp.Save(path,ImageFormat.Png);
    }
  }
  public static void GenerateFireIcon(string path) {
    using(var bmp=new Bitmap(512,512)) using(var g=Graphics.FromImage(bmp)) {
      g.SmoothingMode=SmoothingMode.AntiAlias;
      g.TextRenderingHint=System.Drawing.Text.TextRenderingHint.AntiAliasGridFit;
      // Fire OS 6 adds gray side panels and its own lower shadow to sideloaded icons.
      // Match the flat panel color; let the launcher apply the same shadow everywhere.
      g.Clear(C("#5A5A5A"));
      Mark(g,-58,112,284,C("#7CF4CD"));
      Text(g,"Buzin","Segoe UI",84,FontStyle.Bold,C("#FFFFFF"),205,159);
      Text(g,"CAST","Segoe UI",80,FontStyle.Bold,C("#7CF4CD"),210,260);
      bmp.Save(path,ImageFormat.Png);
    }
  }
  public static void Generate(string folder) {
    Directory.CreateDirectory(folder);
    foreach(bool banner in new[]{false,true}) {
      int w=banner?1280:1024,h=banner?720:1024;
      using(var bmp=new Bitmap(w,h)) using(var g=Graphics.FromImage(bmp)) {
        g.SmoothingMode=SmoothingMode.AntiAlias;g.TextRenderingHint=System.Drawing.Text.TextRenderingHint.AntiAliasGridFit;
        using(var bg=new LinearGradientBrush(new Rectangle(0,0,w,h),C("#142938"),C("#070F19"),38))g.FillRectangle(bg,0,0,w,h);
        using(var pen=new Pen(Color.FromArgb(18,124,244,205),banner?2:3)) {
          g.DrawEllipse(pen,-w/3,-h/2,w,h);g.DrawEllipse(pen,-w/3+80,-h/2+80,w-160,h-160);
        }
        if(banner) {
          using(var p=Round(106,216,246,246,62))using(var b=new LinearGradientBrush(new Rectangle(106,216,246,246),C("#91FFD6"),C("#42DFAE"),45))g.FillPath(b,p);
          Mark(g,123,230,209,C("#0A2823"));
          Text(g,"Buzin", "Segoe UI",126,FontStyle.Bold,C("#F3F7F5"),403,205);
          Text(g,"CAST", "Segoe UI",66,FontStyle.Regular,C("#80F5C8"),412,345);



        } else {
          using(var p=Round(56,56,912,912,208))using(var pen=new Pen(Color.FromArgb(35,160,255,218),3))g.DrawPath(pen,p);
          using(var p=Round(222,222,580,580,150))using(var b=new LinearGradientBrush(new Rectangle(222,222,580,580),C("#93FFDA"),C("#3BD8A6"),48))g.FillPath(b,p);
          Mark(g,260,252,500,C("#092A24"));
        }
        bmp.Save(Path.Combine(folder,banner?"buzin_banner.png":"buzin_icon.png"),ImageFormat.Png);
      }
    }
  }
}
'@
if ($StoreDirectory) {
    New-Item -ItemType Directory -Path $StoreDirectory -Force | Out-Null
    [BuzinBrand]::GenerateStoreIcon((Join-Path $StoreDirectory 'icon-512.png'),512,512)
    [BuzinBrand]::GenerateStoreIcon((Join-Path $StoreDirectory 'icon-114.png'),114,114)
    [BuzinBrand]::GenerateStoreIcon((Join-Path $StoreDirectory 'fire-tv-icon-1280x720.png'),1280,720)
    return
}
[BuzinBrand]::Generate((Join-Path $PSScriptRoot '..\app\src\main\res\drawable-nodpi'))
[BuzinBrand]::GenerateFireIcon((Join-Path $PSScriptRoot '..\app\src\main\res\drawable-nodpi\buzin_fire_icon.png'))
