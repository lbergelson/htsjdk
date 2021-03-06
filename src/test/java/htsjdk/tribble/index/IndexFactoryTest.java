/*
 * The MIT License
 *
 * Copyright (c) 2013 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package htsjdk.tribble.index;

import com.google.common.io.Files;
import htsjdk.HtsjdkTest;
import htsjdk.samtools.SAMSequenceDictionary;
import htsjdk.samtools.SAMSequenceRecord;
import htsjdk.samtools.util.IOUtil;
import htsjdk.samtools.util.Interval;
import htsjdk.tribble.TestUtils;
import htsjdk.tribble.Tribble;
import htsjdk.tribble.TribbleException;
import htsjdk.tribble.bed.BEDCodec;
import htsjdk.tribble.index.tabix.TabixFormat;
import htsjdk.tribble.index.tabix.TabixIndex;
import htsjdk.variant.bcf2.BCF2Codec;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFCodec;
import htsjdk.variant.vcf.VCFFileReader;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;

/**
 * User: jacob
 * Date: 2012-Aug-23
 */
public class IndexFactoryTest extends HtsjdkTest {

    @DataProvider(name = "bedDataProvider")
    public Object[][] getLinearIndexFactoryTypes() {
        return new Object[][] {
                { new File(TestUtils.DATA_DIR, "bed/Unigene.sample.bed") },
                { new File(TestUtils.DATA_DIR, "bed/Unigene.sample.bed.gz") }
        };
    }

    @Test(dataProvider = "bedDataProvider")
    public void testCreateLinearIndexFromBED(final File inputBEDFIle) throws Exception {
        Index index = IndexFactory.createLinearIndex(inputBEDFIle, new BEDCodec());
        String chr = "chr2";

        Assert.assertTrue(index.getSequenceNames().contains(chr));
        Assert.assertTrue(index.containsChromosome(chr));
        Assert.assertEquals(1, index.getSequenceNames().size());
        List<Block> blocks = index.getBlocks(chr, 1, 50);
        Assert.assertEquals(1, blocks.size());

        Block block = blocks.get(0);
        Assert.assertEquals(78, block.getSize());
    }

    @Test(expectedExceptions = TribbleException.MalformedFeatureFile.class, dataProvider = "indexFactoryProvider")
    public void testCreateIndexUnsorted(IndexFactory.IndexType type) throws Exception{
        final File unsortedBedFile = new File(TestUtils.DATA_DIR, "bed/unsorted.bed");
        IndexFactory.createIndex(unsortedBedFile, new BEDCodec(), type);
    }

    @Test(expectedExceptions = TribbleException.MalformedFeatureFile.class, dataProvider = "indexFactoryProvider")
    public void testCreateIndexDiscontinuousContigs(IndexFactory.IndexType type) throws Exception{
        final File discontinuousFile = new File(TestUtils.DATA_DIR,"bed/disconcontigs.bed");
        IndexFactory.createIndex(discontinuousFile, new BEDCodec(), type);
    }

    @DataProvider(name = "indexFactoryProvider")
    public Object[][] getIndexFactoryTypes(){
        return new Object[][] {
                new Object[] { IndexFactory.IndexType.TABIX },
                new Object[] { IndexFactory.IndexType.LINEAR },
                new Object[] { IndexFactory.IndexType.INTERVAL_TREE }
        };
    }

    @Test
    public void testCreateTabixIndexOnBlockCompressed() {
        // index a VCF
        final File inputFileVcf = new File("src/test/resources/htsjdk/tribble/tabix/testTabixIndex.vcf");
        final VCFFileReader readerVcf = new VCFFileReader(inputFileVcf, false);
        final SAMSequenceDictionary vcfDict = readerVcf.getFileHeader().getSequenceDictionary();
        final TabixIndex tabixIndexVcf =
                IndexFactory.createTabixIndex(inputFileVcf, new VCFCodec(), TabixFormat.VCF,
                        vcfDict);

        // index the same bgzipped VCF
        final File inputFileVcfGz = new File("src/test/resources/htsjdk/tribble/tabix/testTabixIndex.vcf.gz");
        final VCFFileReader readerVcfGz = new VCFFileReader(inputFileVcfGz, false);
        final TabixIndex tabixIndexVcfGz =
                IndexFactory.createTabixIndex(inputFileVcfGz, new VCFCodec(), TabixFormat.VCF,
                        readerVcfGz.getFileHeader().getSequenceDictionary());

        // assert that each sequence in the header that represents some VCF row ended up in the index
        // for both the VCF and bgzipped VCF
        for (SAMSequenceRecord samSequenceRecord : vcfDict.getSequences()) {
            Assert.assertTrue(
                    tabixIndexVcf.containsChromosome(samSequenceRecord.getSequenceName()),
                    "Tabix indexed VCF does not contain sequence: " + samSequenceRecord.getSequenceName());

            Assert.assertTrue(
                    tabixIndexVcfGz.containsChromosome(samSequenceRecord.getSequenceName()),
                    "Tabix indexed (bgzipped) VCF does not contain sequence: " + samSequenceRecord.getSequenceName());
        }

    }

    @DataProvider(name = "vcfDataProvider")
    public Object[][] getVCFIndexData(){
        return new Object[][] {
                new Object[] {
                        new File(TestUtils.DATA_DIR, "tabix/4featuresHG38Header.vcf.gz"),
                        new Interval("chr6", 33414233, 118314029)
                },
                new Object[] {
                        new File(TestUtils.DATA_DIR, "tabix/4featuresHG38Header.vcf"),
                        new Interval("chr6", 33414233, 118314029)
                },
        };
    }

    @Test(dataProvider = "vcfDataProvider")
    public void testCreateTabixIndexFromVCF(
            final File inputVCF,
            final Interval queryInterval) throws IOException {
        // copy the original file and create the index for the copy
        final File tempDir = IOUtil.createTempDir("testCreateTabixIndexFromVCF", null);
        tempDir.deleteOnExit();
        final File tmpVCF = new File(tempDir, inputVCF.getName());
        Files.copy(inputVCF, tmpVCF);
        tmpVCF.deleteOnExit();

        // this test creates a TABIX index (.tbi)
        final TabixIndex tabixIndexGz = IndexFactory.createTabixIndex(tmpVCF, new VCFCodec(), null);
        tabixIndexGz.writeBasedOnFeatureFile(tmpVCF);
        final File tmpIndex = Tribble.tabixIndexFile(tmpVCF);
        tmpIndex.deleteOnExit();

        try (final VCFFileReader originalReader = new VCFFileReader(inputVCF,false);
            final VCFFileReader tmpReader = new VCFFileReader(tmpVCF, tmpIndex,true)) {
            Iterator<VariantContext> originalIt = originalReader.iterator();
            Iterator<VariantContext> tmpIt = tmpReader.query(queryInterval.getContig(), queryInterval.getStart(), queryInterval.getEnd());
            while (originalIt.hasNext()) {
                Assert.assertTrue(tmpIt.hasNext(), "variants missing from gzip query");
                VariantContext vcTmp = tmpIt.next();
                VariantContext vcOrig = originalIt.next();
                Assert.assertEquals(vcOrig.getContig(), vcTmp.getContig());
                Assert.assertEquals(vcOrig.getStart(), vcTmp.getStart());
                Assert.assertEquals(vcOrig.getEnd(), vcTmp.getEnd());
            }
        }
    }

    @DataProvider(name = "bcfDataFactory")
    public Object[][] getBCFData(){
        return new Object[][] {
                //TODO: this needs more test cases, including block compressed and indexed, but bcftools can't
                // generate indices for BCF2.1 files, which is all HTSJDK can read, and htsjdk also can't read/write
                // block compressed BCFs (https://github.com/samtools/htsjdk/issues/946)
                new Object[] {
                        new File("src/test/resources/htsjdk/variant/serialization_test.bcf")
                }
        };
    }

    @Test(dataProvider = "bcfDataFactory")
    public void testCreateLinearIndexFromBCF(final File inputBCF) throws IOException {
        // copy the original file and create the index for the copy
        final File tempDir = IOUtil.createTempDir("testCreateIndexFromBCF", null);
        tempDir.deleteOnExit();
        final File tmpBCF = new File(tempDir, inputBCF.getName());
        Files.copy(inputBCF, tmpBCF);
        tmpBCF.deleteOnExit();

        // NOTE: this test creates a LINEAR index (.idx)
        final Index index = IndexFactory.createIndex(tmpBCF, new BCF2Codec(), IndexFactory.IndexType.LINEAR);
        index.writeBasedOnFeatureFile(tmpBCF);
        final File tempIndex = Tribble.indexFile(tmpBCF);
        tempIndex.deleteOnExit();

        try (final VCFFileReader originalReader = new VCFFileReader(inputBCF,false);
            final VCFFileReader tmpReader = new VCFFileReader(tmpBCF, tempIndex,true)) {
            final Iterator<VariantContext> originalIt = originalReader.iterator();
            while (originalIt.hasNext()) {
                // we don't have an externally generated index file for the original input, so iterate through each variant
                // and use the generated index to query for the same variant in the indexed copy of the input
                final VariantContext vcOrig = originalIt.next();
                final Interval queryInterval = new Interval(vcOrig.getContig(), vcOrig.getStart(), vcOrig.getEnd());
                final Iterator<VariantContext> tmpIt = tmpReader.query(queryInterval.getContig(), queryInterval.getStart(), queryInterval.getEnd());
                Assert.assertTrue(tmpIt.hasNext(), "Variant not returned from indexed file");
                final VariantContext vcTmp = tmpIt.next();
                Assert.assertEquals(vcOrig.getContig(), vcTmp.getContig());
                Assert.assertEquals(vcOrig.getStart(), vcTmp.getStart());
                Assert.assertEquals(vcOrig.getEnd(), vcTmp.getEnd());
                Assert.assertFalse(tmpIt.hasNext()); // make sure there is only one matching variant
            }
        }
    }
}
