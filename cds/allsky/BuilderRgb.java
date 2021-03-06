package cds.allsky;

import java.awt.image.IndexColorModel;
import java.io.File;
import java.io.FileInputStream;

import cds.aladin.CanvasColorMap;
import cds.aladin.MyProperties;
import cds.aladin.PlanImage;
import cds.aladin.Tok;
import cds.allsky.Context.JpegMethod;
import cds.fits.Fits;
import cds.moc.HealpixMoc;
import cds.tools.pixtools.Util;

public class BuilderRgb extends Builder {


   private String [] inputs;         // Les paths des HiPS red, green, blue
   private HealpixMoc [] moc;        // Les Mocs de chaque composante
   private MyProperties [] prop;     // Les propri�t�s de chaque composante
   private String [] labels;         // Les labels des HiPS red, green blue
   private String [] transfertFcts;  // Fonction de transfert � appliquer � chaque composante
   private double [] pixelMin,pixelMax,pixelMiddle; // valeur min, middle et max des pixels � utiliser pour d�finir la colormap et la plage
   // du pixelCut - les valeurs sont exprim�es en valeur coding (bzero et bscale non appliqu�s)

   private String output;
   private int width=-1;
   private double [] blank;
   private double [] bscale;
   private double [] bzero;
   private byte [][] tcm;
   private int [] bitpix;
   private int maxOrder=-1;
   private String frame=null;
   private int missing=-1;

   private Mode coaddMode=Mode.REPLACETILE;
   private JpegMethod method;
   private int format;

   private int statNbFile;
   private long statSize;
   private long startTime,totalTime;

   public BuilderRgb(Context context) {
      super(context);
   }

   public Action getAction() { return Action.RGB; }

   public void run() throws Exception {
      build();

      if( !context.isTaskAborting() ) (new BuilderMoc(context)).createMoc(output);
      if( !context.isTaskAborting() ) { (new BuilderAllsky(context)).run(); context.info("ALLSKY file done"); }

   }

   // Demande d'affichage des stats (dans le TabRgb)
   public void showStatistics() {
      context.showRgbStat(statNbFile, statSize, totalTime);
   }

   public void validateContext() throws Exception {

      String path = context.getRgbOutput();
      
      JpegMethod method = context.getRgbMethod();
      format= context.getRgbFormat();
      coaddMode = context.getMode();
      if( coaddMode!=Mode.KEEPTILE && coaddMode!=Mode.REPLACETILE ) {
         if( context instanceof ContextGui ) {
            context.setMode(Mode.REPLACETILE);
         } else throw new Exception("Only KEEPTILE and REPLACETILE modes are supported for RGB HiPS generation");

      }

      String pathRef=null;

      inputs = new String[3];
      labels = new String[3];
      moc = new HealpixMoc[3];
      prop = new MyProperties[3];
      pixelMin = new double[3];
      pixelMiddle = new double[3];
      pixelMax = new double[3];
      transfertFcts = new String[3];

      bitpix = new int[3];
      for( int c=0; c<3; c++ ) bitpix[c]=0;
      blank = new double[3];
      bzero = new double[3];
      bscale = new double[3];
      tcm = new byte[3][];
      this.method=method;

      // Un frame indiqu� => on m�morise sa premi�re lettre.
      if( context.hasFrame() ) frame = context.getFrameName();

      for( int c=0; c<3; c++ ) {
         inputs[c] = context.plansRGB[c];
         if( inputs[c]==null ) {
            if( missing!=-1 ) throw new Exception("HiPS RGB generation required at least 2 original components");
            missing=c;
         } else {

            if( !context.isExistingAllskyDir(inputs[c]) ) {
               throw new Exception("Input HiPS missing ["+inputs[c]+"]");
            }
                  
            if( pathRef==null ) pathRef=inputs[c];

            // R�cup�ration des propri�t�s
            prop[c] = loadProperties( inputs[c] );

            // recup�ration du label de la composante (juste pour de l'info)
            labels[c] = getLabelFromProp( prop[c], inputs[c] );

            // Analyse des param�tres de descriptions du pixelCut et de la colormap
            String s = context.cmsRGB[c];
            if( s==null ) {
               s = getCmParamFromProp( prop[c] );
               if( s==null ) throw new Exception("Unknown pixelcut for "+labels[c]);
            }
            setCmParamExact(s, c);

            // M�morisation de l'ordre le plus profond
            int o = getOrderFromProp( prop[c], inputs[c] );
            if( o>maxOrder ) maxOrder=o;

            // Ajustement de la r�gion qu'il faudra calculer
            HealpixMoc m = moc[c] = loadMoc( inputs[c] );
            if( context.moc==null ) context.moc = m;
            else context.moc = context.moc.union(m);
            
           // V�rification de la coh�rence des syst�mes de coordonn�es
            String f = getFrameFromProp( prop[c] );
            if( frame==null ) frame=f;
            else if( !frame.equals(f) ) throw new Exception("Uncompatible coordsys for "+labels[c]);

         }
      }
      
      // Si le r�pertoire de destination est absent, je donne une valeur par d�faut
      if( path==null ) {
         int n = pathRef.length()-1;
         int offset = Math.max(pathRef.lastIndexOf('/',n),pathRef.lastIndexOf('\\',n));
         path = pathRef.substring(0,offset+1)+"RGBHiPS";
         context.warning("Missing \"out\" parameter. Assuming \""+path+"\"");
      }
      this.output = path;

      if( context instanceof ContextGui ) ((ContextGui)context).mainPanel.clearForms();

      // Pas d'ordre indiqu� => on va utiliser l'ordre de la composante la plus profonde
      if( context.getOrder()==-1 ) {
         context.setOrder(maxOrder);
         context.info("Using order = "+maxOrder);
      } else maxOrder=context.getOrder();

      // Mise � jour du context
      if( !context.hasFrame() ) {
         context.setFrameName(frame);
         context.info("Using coordys = "+context.getFrameName());
      }
      context.setOutputPath(path);

      context.setBitpixOrig(0);


      // m�morisation des informations de colormaps
      for( int c=0; c<3; c++) {
         if( c==missing ) continue;
         String info =  labels[c]+" ["+pixelMin[c]+" "+pixelMiddle[c]+" "+pixelMax[c]+" "+transfertFcts[c]+"]";
         if( c==0 ) context.redInfo=info;
         else if( c==1 ) context.greenInfo=info;
         else context.blueInfo=info;
      }

      // d�termination de la zone � calculer
      if( context.mocArea!=null ) context.moc = context.moc.intersection( context.mocArea );

      context.writePropertiesFile();
   }

   // Chargement d'un MOC, et par d�faut, d'un MOC couvrant tout le ciel
   private HealpixMoc loadMoc( String path ) throws Exception {
      String s = path+Util.FS+"Moc.fits";
      if( !(new File(s)).canRead() ) return new HealpixMoc("0/0-11");
      HealpixMoc m = new HealpixMoc();
      m.read(s);
      return m;
   }

   // R�cup�ration de la premi�re lettre du frame � partir de son fichier de properties
   // et � d�faut c'est du Galactic
   private String getFrameFromProp( MyProperties prop ) throws Exception {
      String s = prop.getProperty( Constante.KEY_HIPS_FRAME );
      if( s==null ) s = prop.getProperty( Constante.OLD_HIPS_FRAME );
      if( s==null ) s="G";
      return context.getCanonicalFrameName(s);
   }

   // R�cup�ration du pixelcut d'une composante � partir de son fichier de properties
   private String getCmParamFromProp( MyProperties prop ) throws Exception {
      String s = prop.getProperty( Constante.KEY_HIPS_PIXEL_CUT );
      if( s==null ) s = prop.getProperty( Constante.OLD_HIPS_PIXEL_CUT );
      return s;
   }

   // R�cup�ration du label d'une composante � partir de son fichier de properties
   // et � d�faut de son nom de r�pertoire
   private String getLabelFromProp(MyProperties prop,String path) throws Exception {
      String s=null;
      if( prop!=null ) {
         s = prop.getProperty( Constante.KEY_OBS_COLLECTION);
         if( s==null ) prop.getProperty( Constante.OLD_OBS_COLLECTION);
      }
      if( s==null ) {
         int offset = path.lastIndexOf('/');
         if( offset==-1 ) offset = path.lastIndexOf('\\');
         s=path.substring(offset+1);
      }
      return s;
   }

   // R�cup�ration du order d'une composante � partir de son fichier de properties
   // et � d�faut en scannant son r�pertoire
   private int getOrderFromProp(MyProperties prop,String path) throws Exception {
      int order=-1;
      String s=null;
      if( prop!=null ) {
         s = prop.getProperty( Constante.KEY_HIPS_ORDER);
         if( s==null ) prop.getProperty( Constante.OLD_HIPS_ORDER);
         try { order = Integer.parseInt(s); } catch( Exception e) {}
      }
      if( order==-1 ) order = Util.getMaxOrderByPath( path );
      return order;
   }

   // Chargement d'un fichier de propri�t�s
   private MyProperties loadProperties(String path) throws Exception {
      MyProperties prop;
      String propFile = path+Util.FS+Constante.FILE_PROPERTIES;
      prop = new MyProperties();
      File f = new File( propFile );
      if( f.exists() ) {
         if( !f.canRead() || !f.canWrite() ) throw new Exception("Propertie file not available ! ["+propFile+"]");
         FileInputStream in = new FileInputStream(propFile);
         prop.load(in);
         in.close();
      }
      return prop;
   }

   /** Pr�paration de la table des couleurs pour la composante c
    * @param s : format: min [med] max [fct]  (repr�sente les valeurs physiques des pixels comme repr�sent�es sur l'histogramme des pixels)
    */
   private void setCmParamExact(String s,int c) throws Exception {
      Tok tok = new Tok(s);
      double min;
      double max;
      try {
         min = Double.parseDouble(tok.nextToken());
         max = Double.parseDouble(tok.nextToken());
      } catch( Exception e1 ) {
         throw new Exception("Colormap parameter error ["+s+"] => usage: min [middle] max [fct]");
      }
      int transfertFct;

      if( max<=min ) throw new Exception("Colormap parameter error ["+s+"] => max<=min");
      double med = Double.NaN;
      String fct = null;
      if( tok.hasMoreTokens() ) {
         String s1 = tok.nextToken();
         double x;
         try {
            x = Double.parseDouble(s1);
            med=max;
            max=x;
         } catch( Exception e ) {
            fct=s1;
         }
      }
      if( tok.hasMoreTokens() ) fct=tok.nextToken();

      if( fct!=null ) {
         int i = cds.tools.Util.indexInArrayOf(fct, PlanImage.TRANSFERTFCT, true);
         if( i<0 ) throw new Exception("Colormap parameter error ["+s+"] => Unknown transfert function ["+fct+"]");
         transfertFct=i;

      } else transfertFct=PlanImage.LINEAR;

      transfertFcts[c] = PlanImage.getTransfertFctInfo( transfertFct);

      // r�cup�ration du bzero et bscale
      double bzero=0,bscale=1;
      Fits f = new Fits();
      String name = inputs[c]+Util.FS+"Norder3"+Util.FS+"Allsky.fits";
      File file = new File( name );
      if( !file.canRead() ) throw new Exception("Cannot determine BZERO and BSCALE for component "+c+" => missing Allsky.fits");
      f.loadHeaderFITS( name );
      try { bzero = f.headerFits.getDoubleFromHeader("BZERO"); } catch( Exception e ) { }
      try { bscale = f.headerFits.getDoubleFromHeader("BSCALE"); } catch( Exception e ) { }

      pixelMin[c]    = (min-bzero)/bscale;
      pixelMiddle[c] = (med-bzero)/bscale;
      pixelMax[c]    = (max-bzero)/bscale;

      int tr1 = 128;
      if( !Double.isNaN(med) ) {
         if( med<min || med>max ) throw new Exception("Colormap parameter error ["+s+"] => med<min || med>max");
         tr1 = (int)( ( (med-min)/(max-min) ) *255 );
      }

      context.info("Using pixelCut "+L(c)+" = "+min+(Double.isNaN(med)?"":" "+med)+" "+max+
            (transfertFct!=PlanImage.LINEAR ?" "+PlanImage.getTransfertFctInfo(transfertFct):""));

      IndexColorModel cm = CanvasColorMap.getCM(0, tr1, 255, false, PlanImage.CMGRAY, transfertFct, true );
      tcm[c] = cds.tools.Util.getTableCM(cm, 2);
   }

   private String L(int c) { return c==0?"red":c==1?"green":"blue"; }

   private void initStat() { statNbFile=0; statSize=0; startTime = System.currentTimeMillis(); }

   // Mise � jour des stats
   private void updateStat(File f) {
      statNbFile++;
      statSize += f.length();
      totalTime = System.currentTimeMillis()-startTime;
   }

   private void updateStat(int deltaNbFile) {
      statNbFile+=deltaNbFile;
      totalTime = System.currentTimeMillis()-startTime;
   }

   public void build() throws Exception  {
      initStat();
      for( int i=0; i<768; i++ ) {
         if( context.isTaskAborting() ) new Exception("Task abort !");
         // Si le fichier existe d�j� on ne l'�crase pas
         String rgbfile = Util.getFilePath(output,3, i)+Constante.TILE_EXTENSION[format];
         if( !context.isInMocTree(3, i) ) continue;
         if ((new File(rgbfile)).exists()) continue;
         createRGB(3,i);
         context.setProgressLastNorder3(i);
      }
   }

   // G�n�ration des RGB r�cursivement en repartant du niveau de meilleure r�solution
   // afin de pouvoir utiliser un calcul de m�diane sur chacune des composantes R,G et B
   private Fits [] createRGB(int order, long npix) throws Exception {

      if( context.isTaskAborting() ) new Exception("Task abort !");

      // si on n'est pas dans le Moc, il faut retourner les 4 fits de son niveau
      // pour la construction de l'arborescence...
      if( !context.isInMocTree(order,npix) ) return createLeaveRGB(order, npix);

      // si le losange a d�j� �t� calcul� on renvoie les 4 fits de son niveau
      if( coaddMode==Mode.KEEPTILE ) {
         if( findLeaf(order, npix) ) {
            Fits [] oldOut = createLeaveRGB(order, npix);
            HealpixMoc moc = context.getRegion();
            moc = moc.intersection(new HealpixMoc(order+"/"+npix));
            int nbTiles = (int)moc.getUsedArea();
            updateStat(nbTiles);
            return oldOut;
         }
      }

      // S'il n'existe pas au-moins 1 tuile de la composante � cette position, c'est une branche morte
      boolean trouve=false;
      for( int c=0; !trouve && c<3; c++ ) {
         if( c==missing ) continue;
// JE NE PASSE PLUS PAR L'EXISTENCE DU FICHIER CAR SI LES MAXORDER SONT DIFFERENTS, CA NE MARCHE PLUS
//         String file = Util.getFilePath(inputs[c],order,npix)+".fits";
//         trouve = new File(file).exists();
         trouve = moc[c].isIntersecting(order, npix);
      }
      if( !trouve ) return null;

      Fits [] out = null;

      // Branche terminale
      boolean leaf=false;
      if( order==maxOrder ) {
         out = createLeaveRGB(order, npix);
         leaf=true;
      }

      // Noeud interm�diaire
      else {
         Fits [][] fils = new Fits[4][3];
         boolean found = false;
         for( int i=0; i<4; i++ ) {
            fils[i] = createRGB(order+1,npix*4+i);
            if( fils[i] != null && !found ) found = true;
         }
         if( found ) out = createNodeRGB(fils);
      }
      if( out!=null ) generateRGB(out, order, npix, leaf);
      return out;
   }

   private boolean findLeaf(int order, long npix) throws Exception {
      String filename = Util.getFilePath(output,order, npix)+Constante.TILE_EXTENSION[format];
      File f = new File(filename);
      return f.exists();
   }

   // G�n�ration d'un noeud par la m�diane pour les 3 composantes
   // (on ne g�n�re pas encore le RGB (voir generateRGB(...))
   private Fits [] createNodeRGB(Fits [][] fils) throws Exception {

      Fits [] out = new Fits[3];
      if( context.isTaskAborting() ) throw new Exception("Task abort !");

      for( int c=0; c<3; c++ ) {
         out[c] = new Fits(width,width,bitpix[c]);
         out[c].setBlank(blank[c]);
         out[c].setBzero(bzero[c]);
         out[c].setBscale(bscale[c]);
      }

      for( int dg=0; dg<2; dg++ ) {
         for( int hb=0; hb<2; hb++ ) {
            int quad = dg<<1 | hb;
            int offX = (dg*width)/2;
            int offY = ((1-hb)*width)/2;

            for( int c=0; c<3; c++ ) {
               if( c==missing ) continue;
               Fits in = fils[quad]==null ? null : fils[quad][c];
               double p[] = new double[4];
               double coef[] = new double[4];

               for( int y=0; y<width; y+=2 ) {
                  for( int x=0; x<width; x+=2 ) {

                     double pix=blank[c];
                     if( in!=null ) {

                        // On prend la moyenne (sans prendre en compte les BLANK)
                        if( method==Context.JpegMethod.MEAN ) {
                           double totalCoef=0;
                           for( int i=0; i<4; i++ ) {
                              int dx = i==1 || i==3 ? 1 : 0;
                              int dy = i>=2 ? 1 : 0;
                              p[i] = in.getPixelDouble(x+dx,y+dy);
                              if( in.isBlankPixel(p[i]) ) coef[i]=0;
                              else coef[i]=1;
                              totalCoef+=coef[i];
                           }
                           if( totalCoef!=0 ) {
                              pix = 0;
                              for( int i=0; i<4; i++ ) {
                                 if( coef[i]!=0 ) pix += p[i]*(coef[i]/totalCoef);
                              }
                           }

                           // On garde la valeur m�diane (les BLANK seront automatiquement non retenus)
                        } else {

                           double p1 = in.getPixelDouble(x,y);
                           if( in.isBlankPixel(p1) ) p1=Double.NaN;
                           double p2 = in.getPixelDouble(x+1,y);
                           if( in.isBlankPixel(p2) ) p1=Double.NaN;
                           double p3 = in.getPixelDouble(x,y+1);
                           if( in.isBlankPixel(p3) ) p1=Double.NaN;
                           double p4 = in.getPixelDouble(x+1,y+1);
                           if( in.isBlankPixel(p4) ) p1=Double.NaN;

                           if( p1>p2 && (p1<p3 || p1<p4) || p1<p2 && (p1>p3 || p1>p4) ) pix=p1;
                           else if( p2>p1 && (p2<p3 || p2<p4) || p2<p1 && (p2>p3 || p2>p4) ) pix=p2;
                           else if( p3>p1 && (p3<p2 || p3<p4) || p3<p1 && (p3>p2 || p3>p4) ) pix=p3;
                           else pix=p4;
                        }
                     }

                     out[c].setPixelDouble(offX+(x/2), offY+(y/2), pix);
                  }
               }
            }
         }
      }

      for( int j=0; j<4; j++ ) {
         for( int c=0; c<3; c++ ) {
            if( c==missing ) continue;
            if( fils[j]!=null && fils[j][c]!=null ) fils[j][c].free();
         }
      }

      return out;
   }

   // G�n�ration d'une feuille terminale (=> simple chargement des composantes)
   private Fits [] createLeaveRGB(int order, long npix) throws Exception {
      if( context.isTaskAborting() ) new Exception("Task abort !");
      Fits[] out =null;
      out = new Fits[3];

      // Chargement des 3 (ou �ventuellement 2) composantes
      for( int c=0; c<3; c++ ) {
         if( c==missing ) continue;

         if( !moc[c].isIntersecting(order, npix)) out[c]=null;
         else {
            try {
               out[c] = createSubLeaveRGB(order,npix,c);
            } catch( Exception e ) { out[c]=null; }
         }

         // Initialisation des constantes pour cette composante
         if( out[c]!=null && bitpix[c]==0 ) {
            bitpix[c]=out[c].bitpix;
            blank[c]=out[c].blank;
            bscale[c]=out[c].bscale;
            bzero[c]=out[c].bzero;
            if( width==-1 ) width = out[c].width;  // La largeur d'un losange est la m�me qq soit la couleur
         }
      }
      if( out[0]==null && out[1]==null && out[2]==null ) out=null;
      return out;
   }

   // Dans le cas o� l'ordre max d'un HiPS est inf�rieur � la demande, il
   // faut prendre la tuile du premier niveau sup�rieur qui contient la zone,
   // et g�n�rer � la vol�e la sous-tuile
   private Fits createSubLeaveRGB(int order, long npix,int c) throws Exception {
      int o = order;
      String file=null;

      // D�termination du premier ordre parent qui dispose d'une tuile
      long n=npix;
      for( o=order; o>=3; o--, n/=4 ) {
         file =  Util.getFilePath( inputs[c], o, n)+".fits";
         if( (new File(file)).exists() ) break;
      }

      // A priori aucune => bizarre !! le moc devait �tre non renseign�
      if( o<3 ) return null;

      // Chargement de la tuile "ancetre"
      Fits fits = new Fits();
      fits.loadFITS( file );

      // On est � l'ordre demand� => rien � faire
      if( o==order ) return fits;

      // D�termination de la position (xc,yc coin sup gauche) et de la taille
      // de la zone de pixel � extraire (width*width), et de la taille du pixel final (gap)
      int xc=0, yc=fits.height-1;
      int width = fits.width;
      for( int i=order; i>o; i--) width/=2;

      int gap=1;
      int w = width;
      for( int i=order; i>o; i--, npix/=4L, w*=2) {
         gap *= 2;
         int child = (int)( npix%4L);
         int offsetX = child==2 || child==3 ? w : 0;
         int offsetY = child==1 || child==3 ? w : 0;
         xc = xc + offsetX;
         yc = yc - offsetY;
      }

      int length = Math.abs(fits.bitpix)/8;

      // Extraction des pixels dans un buffer temporaire
      byte [] pixels = new byte[ width*width*length ];
      for( int y=width-1; y>=0; y--) {
         for( int x=0; x<width; x++) {
            int srcPos  = ( (yc-(width-y-1))*fits.width + (xc+x) )*length;
            int destPos = ( y*width+x )*length;
            System.arraycopy(fits.pixels, srcPos, pixels, destPos, length);
         }
      }

      // Agrandissement des pixels
      for( int y=width-1; y>=0; y--) {
         for( int x=0; x<width; x++ ) {
            int srcPos = ( y*width+x ) *length;
            for( int gapy=0; gapy<gap; gapy++) {
               for( int gapx=0; gapx<gap; gapx++ ) {
                  int destPos = ( (y*gap+gapy)*fits.width+(x*gap+gapx) ) *length;
                  System.arraycopy(pixels, srcPos, fits.pixels, destPos, length);
               }
            }
         }
      }

      return fits;
   }

   // g�n�ration du RGB � partir des composantes
   private void generateRGB(Fits [] out, int order, long npix, boolean leaf) throws Exception {
      byte [][] pixx8 = new byte [3][];

      // Passage en 8 bits pour chaque composante
      for( int c=0; c<3; c++ ) {
         if( c==missing || out[c]==null ) continue;
         pixx8[c] = out[c].toPix8( pixelMin[c],pixelMax[c], tcm[c],
               format==Constante.TILE_PNG ? Fits.PIX_255 : Fits.PIX_256);
      }

      Fits rgb = new Fits(width,width,0);
      int [] pix8 = new int[3];
      for( int i=width*width-1; i>=0; i-- ) {
         int tot = 0;  // Pour faire la moyenne en cas d'une composante manquante
         for( int c=0; c<3; c++ ) {
            if( c==missing ) continue;
            if( out[c]==null ) pix8[c]=0;
            else {
               pix8[c] = 0xFF & pixx8[c][i];
               tot += pix8[c];
            }
         }
         if( missing!=-1 ) pix8[missing] = tot/2;
         int pix;
         if( tot==0 ) pix=0x00;
         else {
            pix = 0xFF;
            for( int c=0; c<3; c++ ) pix = (pix<<8) | pix8[c];
         }
         rgb.rgb[i]=pix;
      }
      String file="";

      file = Util.getFilePath(output,order, npix)+Constante.TILE_EXTENSION[format];
      rgb.writeRGBPreview(file,Constante.TILE_MODE[format]);
      rgb.free();

      if( leaf ) {
         File f = new File(file);
         updateStat(f);
      }
   }
}
