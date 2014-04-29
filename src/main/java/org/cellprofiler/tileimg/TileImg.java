/**
 * CellProfiler is distributed under the GNU General Public License.
 * See the accompanying file LICENSE for details.
 *
 * Copyright (c) 2003-2009 Massachusetts Institute of Technology
 * Copyright (c) 2009-2014 Broad Institute
 * All rights reserved.
 * 
 * Please see the AUTHORS file for credits.
 * 
 * Website: http://www.cellprofiler.org
 */

package org.cellprofiler.tileimg;

import java.io.File;
import java.io.IOException;

import loci.common.services.DependencyException;
import loci.common.services.ServiceException;
import loci.common.services.ServiceFactory;
import loci.formats.FormatException;
import loci.formats.ImageReader;
import loci.formats.ImageWriter;
import loci.formats.MetadataTools;
import loci.formats.codec.CodecOptions;
import loci.formats.meta.IMetadata;
import loci.formats.meta.MetadataConverter;
import loci.formats.meta.MetadataRetrieve;
import loci.formats.meta.MetadataStore;
import loci.formats.ome.OMEXMLMetadata;
import loci.formats.out.OMETiffWriter;
import loci.formats.out.TiffWriter;
import loci.formats.services.OMEXMLService;
import loci.formats.tiff.IFD;

import ome.xml.model.enums.DimensionOrder;
import ome.xml.model.primitives.PositiveInteger;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Lee Kamentsky
 *
 */
public class TileImg {
	private static final String OUTPUT_FOLDER = "output-folder";
	private static final String INPUT_FILE = "input-file";
	private static final String OVERLAP = "overlap";
	private static final String WIDTH = "width";
	private static final String HEIGHT = "height";
	private static final int DEFAULT_HEIGHT = 1024;
	private static final int DEFAULT_WIDTH = 1024;
	private static final int DEFAULT_OVERLAP = 0;
	static final private Logger logger = LoggerFactory.getLogger(TileImg.class); 
	/**
	 * @param args
	 */
	public static void main(String[] args) {
		Options options = new Options();
		options.addOption("?", "help", false, "Print help usage");
		options.addOption("h", HEIGHT, true, "The height of each tile");
		options.addOption("w", WIDTH, true, "The width of each tile");
		options.addOption("o", OVERLAP, true, "The amount of pixels shared between adjacent tiles");
		Option option = new Option("i", INPUT_FILE, true, "The location of the input image file");
		option.setRequired(true);
		options.addOption(option);
		option = new Option("o", OUTPUT_FOLDER, true, "Store tiled images in this folder");
		option.setRequired(true);
		options.addOption(option);
		try {
			CommandLine cmdline = new PosixParser().parse(options, args);
			if (cmdline.hasOption("help")) {
				HelpFormatter hf = new HelpFormatter();
				hf.printHelp("tileimg", options);
			}
			int height = cmdline.hasOption(HEIGHT)?Integer.valueOf(cmdline.getOptionValue(HEIGHT)):DEFAULT_HEIGHT;
			int width = cmdline.hasOption(WIDTH)?Integer.valueOf(cmdline.getOptionValue(WIDTH)):DEFAULT_WIDTH;
			int overlap = cmdline.hasOption(OVERLAP)?Integer.valueOf(cmdline.getOptionValue(OVERLAP)): DEFAULT_OVERLAP;
			String inputFile = cmdline.getOptionValue(INPUT_FILE);
			final File inputIOFile = new File(inputFile);
			if (! inputIOFile.canRead()) {
				logger.error(String.format("Can't read from %s", inputFile));
				return;
			}
			String outputFolder = cmdline.getOptionValue(OUTPUT_FOLDER);
			File outputDir = new File(outputFolder);
			if (! outputDir.exists()) {
				if (! outputDir.mkdirs()) {
					logger.error(String.format("Failed to create folder: %s", outputFolder));
					return;
				}
			}
			String rootName = inputIOFile.getName();
			String format = String.format("%s_xoff%%d_yoff%%d_series%%d_index%%d.tif",
					rootName.subSequence(0, rootName.lastIndexOf(".")));

			ImageReader rdr = new ImageReader();
			rdr.setGroupFiles(false);
			rdr.setAllowOpenFiles(true);
			ServiceFactory factory = new ServiceFactory();
			OMEXMLService service = factory.getInstance(OMEXMLService.class);
			OMEXMLMetadata store = service.createOMEXMLMetadata();
			rdr.setMetadataStore(store);
			rdr.setId(inputFile);
			for (int series=0; series<rdr.getSeriesCount(); series++) {
				rdr.setSeries(series);
				int imageHeight = rdr.getSizeY();
				int imageWidth = rdr.getSizeX();
				int nVerticalTiles = (imageHeight + height - 1) / (height - overlap);
				int nHorizTiles = (imageWidth + width - 1) / (width - overlap);
				int adjTileWidth = imageWidth / nHorizTiles;
				int adjTileHeight = imageHeight / nVerticalTiles;
				for (int index=0; index < rdr.getImageCount(); index++) {
					for (int xIndex=0; xIndex < nHorizTiles; xIndex++) {
						int xCenter = width / 2 + adjTileWidth * xIndex;
						int xLeftEdge = Math.max(0, xCenter - width/2);
						int xRightEdge = Math.min(imageWidth, xCenter + width / 2);
						for (int yIndex=0; yIndex < nVerticalTiles; yIndex++) {
							int yCenter = height / 2 + adjTileHeight * yIndex;
							int yTopEdge = Math.max(0, yCenter - height/2);
							int yBottomEdge = Math.min(imageHeight, yCenter + height / 2);
							final int tileWidth = xRightEdge-xLeftEdge;
							final int tileHeight = yBottomEdge-yTopEdge;
							byte [] buf = rdr.openBytes(index, xLeftEdge, yTopEdge, tileWidth, tileHeight);
							String filename = String.format(format, xLeftEdge, yTopEdge, series, index);
							File outputFile = new File(outputDir, filename);
							if (outputFile.exists()) outputFile.delete();
							ImageWriter writer = new ImageWriter();
							OMEXMLMetadata metadata = service.createOMEXMLMetadata();
							metadata.setImageID(store.getImageID(series), 0);
							metadata.setPixelsID(store.getPixelsID(index), 0);
							for (int channelIdx=0; channelIdx < store.getChannelCount(index); channelIdx++) {
								metadata.setChannelID(store.getChannelID(index, channelIdx), 0, channelIdx);
								metadata.setChannelName(store.getChannelName(index, channelIdx), 0, channelIdx);
								metadata.setChannelSamplesPerPixel(store.getChannelSamplesPerPixel(index, channelIdx), 0, channelIdx);
							}
							metadata.setPixelsBigEndian(store.getPixelsBigEndian(index), 0);
							metadata.setPixelsSignificantBits(store.getPixelsSignificantBits(index), 0);
							metadata.setPixelsType(store.getPixelsType(index), 0);
							metadata.setPixelsDimensionOrder(DimensionOrder.XYCZT, 0);
							metadata.setPixelsSizeX(new PositiveInteger(tileWidth), 0);
							metadata.setPixelsSizeY(new PositiveInteger(tileHeight), 0);
							final PositiveInteger one = new PositiveInteger(1);
							if (store.getChannelCount(index) == 1)
								metadata.setPixelsSizeC(store.getChannelSamplesPerPixel(index, 0), 0);
							metadata.setPixelsSizeT(one, 0);
							metadata.setPixelsSizeZ(one, 0);
							writer.setMetadataRetrieve(metadata);
							writer.setWriteSequentially(true);
							writer.setInterleaved(rdr.isInterleaved());
							writer.setId(outputFile.getAbsolutePath());
							if (writer.getWriter() instanceof TiffWriter) {
								TiffWriter tiffWriter = (TiffWriter)(writer.getWriter());
								IFD ifd = new IFD();
								ifd.putIFDValue(IFD.ROWS_PER_STRIP, new long[] {tileHeight});
								tiffWriter.saveBytes(0, buf, ifd);
							} else {
								writer.saveBytes(0, buf);
							}
							writer.close();
							logger.info(String.format("Wrote %s", outputFile.getName()));
						}
					}
				}
			}
			
		} catch (ParseException e) {
			HelpFormatter hf = new HelpFormatter();
			hf.printHelp("tileimg", options);
		} catch (FormatException e) {
			logger.error(e.getMessage());
		} catch (IOException e) {
			logger.error(e.getMessage());
		} catch (ServiceException e) {
			logger.error(e.getMessage());
		} catch (DependencyException e) {
			logger.error(e.getMessage());
		}

	}

}
