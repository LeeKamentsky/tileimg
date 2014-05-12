package org.cellprofiler.tileimg;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

import ome.xml.model.enums.DimensionOrder;
import ome.xml.model.enums.PixelType;
import ome.xml.model.primitives.PositiveInteger;

import loci.common.services.ServiceFactory;
import loci.formats.FormatException;
import loci.formats.ImageReader;
import loci.formats.in.OMETiffReader;
import loci.formats.ome.OMEXMLMetadata;
import loci.formats.out.OMETiffWriter;
import loci.formats.services.OMEXMLService;

public class TestTileimg {
	static class TestCase {
		final int nSeries;
		final int nPlanes;
		final int height;
		final int width;
		final int tileHeight;
		final int tileWidth;
		final int overlap;
		final boolean skip;
		final boolean pad;
		final File imageDir;
		final byte [][][] imageData;
		final Random random;
		File inputFile;
		TestCase(int nSeries, int nPlanes, int height, int width, int tileHeight, int tileWidth, int overlap, boolean skip, boolean pad) throws IOException, NoSuchAlgorithmException {
			this.nSeries = nSeries;
			this.nPlanes = nPlanes;
			this.height = height;
			this.width = width;
			this.tileHeight = tileHeight;
			this.tileWidth = tileWidth;
			this.overlap = overlap;
			this.skip = skip;
			this.pad = pad;
			imageData = new byte [nSeries][nPlanes][];
			MessageDigest md = MessageDigest.getInstance("sha1");
			for (String s: new String [] { Integer.toString(nPlanes), Integer.toString(height), 
					Integer.toString(width), Integer.toString(tileHeight), Integer.toString(overlap),
					Boolean.toString(skip), Boolean.toString(pad)}) {
				md.update(s.getBytes());
			}
			random = new Random(ByteBuffer.wrap(md.digest()).getLong());
			File fakeFile = File.createTempFile("test", null);
			String path = fakeFile.getAbsolutePath();
			fakeFile.delete();
			imageDir = new File(path);
			assertTrue(imageDir.mkdir());
		}
		
		void makeImageFile() {
			OMETiffWriter writer = new OMETiffWriter();
			try {
				inputFile = File.createTempFile("test", writer.getSuffixes()[0]);
				inputFile.deleteOnExit();
				ServiceFactory factory = new ServiceFactory();
				OMEXMLService service = factory.getInstance(OMEXMLService.class);
				OMEXMLMetadata store = service.createOMEXMLMetadata();
				for (int series=0; series < nSeries; series++) {
					store.setPixelsBigEndian(ByteOrder.nativeOrder().equals(ByteOrder.BIG_ENDIAN), series);
					store.setPixelsDimensionOrder(DimensionOrder.XYCZT, series);
					store.setPixelsType(PixelType.UINT8, series);
					store.setChannelSamplesPerPixel(new PositiveInteger(1), series, 0);
					store.setPixelsSizeX(new PositiveInteger(width), series);
					store.setPixelsSizeY(new PositiveInteger(height), series);
					store.setPixelsSizeC(new PositiveInteger(1), series);
					store.setPixelsSizeZ(new PositiveInteger(1), series);
					store.setPixelsSizeT(new PositiveInteger(nPlanes), series);
					store.setImageID(String.format("Image%d", series+1), series);
					store.setPixelsID(String.format("Pixels%d", series+1), series);
					store.setChannelID("Monochrome", series, 0);
				}
				writer.setMetadataRetrieve(store);
				writer.setId(inputFile.getAbsolutePath());
				for (int series = 0; series < nSeries; series++) {
					writer.setSeries(series);
					for (int index = 0; index < nPlanes; index++) {
						byte [] data = new byte [width * height];
						random.nextBytes(data);
						imageData[series][index] = data;
						writer.saveBytes(index, data);
					}
				}
				writer.close();
			} catch (Exception e) {
				e.printStackTrace();
				fail();
			} 
		}
		void run() {
			ArrayList<String> args = new ArrayList<String>();
			args.add("-i"); args.add(inputFile.getAbsolutePath());
			args.add("-o"); args.add(imageDir.getAbsolutePath());
			args.add("-h"); args.add(Integer.toString(tileHeight));
			args.add("-w"); args.add(Integer.toString(tileWidth));
			args.add("-v"); args.add(Integer.toString(overlap));
			if (skip) args.add("-s");
			else if (pad) args.add("-p");
			TileImg.main(args.toArray(new String[args.size()]));
		}
		
		void check() {
			OMETiffReader readerIn = new OMETiffReader();
			try {
				Pattern p = Pattern.compile("xoff(\\d+)_yoff(\\d+)_series(\\d+)_index(\\d+)");
				Map<List<Integer>, String> filelist = new HashMap<List<Integer>, String>();
				for (String filename:imageDir.list()) {
					Matcher m = p.matcher(filename);
					if (m.find()){
						List<Integer> key = new ArrayList<Integer>(); 
						for (int i=0; i<m.groupCount(); i++) {
							key.add(Integer.decode(m.group(i+1)));
						}
						filelist.put(key, filename);
					}
				}
				readerIn.setId(inputFile.getAbsolutePath());
				Integer [] key = new Integer[4];
				for (int series =0; series < nSeries; series++) {
					key[2] = new Integer(series);
					readerIn.setSeries(series);
					for (int index=0; index < nPlanes; index++) {
						key[3] = new Integer(index);
						for (int x=0; x<width-overlap;) {
							int sizeX = Integer.MAX_VALUE;
							key[0] = new Integer(x);
							for (int y=0; y<height-overlap;) {
								key[1] = new Integer(y);
								List<Integer> lKey = Arrays.asList(key);
								assertTrue(filelist.containsKey(lKey));
								File outFile = new File(imageDir, filelist.get(lKey));
								filelist.remove(lKey);
								ImageReader readerOut = new ImageReader();
								readerOut.setId(outFile.getAbsolutePath());
								sizeX = readerOut.getSizeX();
								int xEnd = x + sizeX;
								final int sizeY = readerOut.getSizeY();
								int yEnd = y + sizeY;
								if (! pad) {
									assertTrue(xEnd <= width);
									assertTrue(yEnd <= height);
								}
								if (skip || pad) {
									assertEquals(tileWidth, sizeX);
									assertEquals(tileHeight, sizeY);
								} else {
									assertTrue(sizeX <= tileWidth);
									assertTrue(sizeY <= tileHeight);
								}
								final int sx = Math.min(width-x, sizeX);
								final int sy = Math.min(height-y, sizeY);
								final byte [] expected = readerIn.openBytes(index, x, y, sx, sy);
								final byte [] actual = readerOut.openBytes(0, 0, 0, sx, sy);
								assertArrayEquals(expected, actual);
								y += sizeY - overlap;
								if (skip && (height - y < tileHeight )) break;
							}
							x += sizeX - overlap;
							if (skip && (width - x < tileWidth)) break;
						}
						
					}
				}
				assertEquals(0, filelist.size());
			} catch (FormatException e) {
				e.printStackTrace();
				fail();
			} catch (IOException e) {
				e.printStackTrace();
				fail();
			}
			
			
		}
		
		static void tc(int nSeries, int nPlanes, int height, int width, int tileHeight, int tileWidth, int overlap, boolean skip, boolean pad) {
			try {
				TestCase t = new TestCase(nSeries, nPlanes, height, width, tileHeight, tileWidth, overlap, skip, pad);
				t.makeImageFile();
				t.run();
				t.check();
			} catch (NoSuchAlgorithmException e) {
				e.printStackTrace();
				fail();
			} catch (IOException e) {
				e.printStackTrace();
				fail();
			}
		}

		/* (non-Javadoc)
		 * @see java.lang.Object#finalize()
		 */
		@Override
		protected void finalize() throws Throwable {
			System.gc();
			for (File file:imageDir.listFiles()) {
				file.delete();
			}
			imageDir.delete();
			super.finalize();
		}
		
	}
	@Test
	public void one() {
		TestCase.tc(1, 1, 10, 20, 10, 20, 0, false, false);
	}
	@Test
	public void twoXtwoExact() {
		TestCase.tc(1, 1, 10, 20, 5, 10, 0, false, false);
	}
	@Test
	public void twoXtwoFit() {
		TestCase.tc(1, 1, 15, 30, 7, 12, 0, false, false);
	}
	@Test
	public void twoXtwoSkip() {
		TestCase.tc(1, 1, 15, 30, 7, 12, 0, true, false);
	}
	@Test
	public void twoXtwoPad() {
		TestCase.tc(1, 1, 15, 30, 7, 12, 0, false, true);
	}
	@Test
	public void stacks() {
		TestCase.tc(1, 3, 10, 20, 5, 10, 0, false, false);
	}
	@Test
	public void series() {
		TestCase.tc(3, 1, 10, 20, 5, 10, 0, false, false);
	}
	@Test
	public void stacksAndSeries() {
		TestCase.tc(3, 5, 10, 20, 5, 10, 0, false, false);
	}
	@Test
	public void overlap() {
		TestCase.tc(1, 1, 28, 58, 10, 20, 2, false, false);
	}
}
