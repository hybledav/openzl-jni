package io.github.hybledav.utils.sao;

/**
 * Parsed star record from SAO catalog binary format.
 */
public final class SaoStarRecord {
    public final int catalogNumber;
    public final int raMilliarcSec;
    public final int decMilliarcSec;
    public final byte spectral0;
    public final byte spectral1;
    public final int magnitudeCentimags;
    public final int raPmMicroArcsecPerYear;
    public final int decPmMicroArcsecPerYear;

    public SaoStarRecord(int catalogNumber,
                         int raMilliarcSec,
                         int decMilliarcSec,
                         byte spectral0,
                         byte spectral1,
                         int magnitudeCentimags,
                         int raPmMicroArcsecPerYear,
                         int decPmMicroArcsecPerYear) {
        this.catalogNumber = catalogNumber;
        this.raMilliarcSec = raMilliarcSec;
        this.decMilliarcSec = decMilliarcSec;
        this.spectral0 = spectral0;
        this.spectral1 = spectral1;
        this.magnitudeCentimags = magnitudeCentimags;
        this.raPmMicroArcsecPerYear = raPmMicroArcsecPerYear;
        this.decPmMicroArcsecPerYear = decPmMicroArcsecPerYear;
    }
}
