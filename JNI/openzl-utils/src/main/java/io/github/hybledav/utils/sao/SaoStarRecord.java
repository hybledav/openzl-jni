package io.github.hybledav.utils.sao;

/**
 * Parsed star record from SAO catalog binary format.
 */
public final class SaoStarRecord {
    public final int catalogId;
    public final int saoNumber;
    public final int raRaw;
    public final int decRaw;
    public final byte spectral0;
    public final byte spectral1;
    public final int magnitudeFloatBits;
    public final int raPmFloatBits;
    public final int decPmFloatBits;
    public final int hd;
    public final int dm;
    public final int gc;

    public SaoStarRecord(int catalogId,
                         int saoNumber,
                         int raRaw,
                         int decRaw,
                         byte spectral0,
                         byte spectral1,
                         int magnitudeFloatBits,
                         int raPmFloatBits,
                         int decPmFloatBits,
                         int hd,
                         int dm,
                         int gc) {
        this.catalogId = catalogId;
        this.saoNumber = saoNumber;
        this.raRaw = raRaw;
        this.decRaw = decRaw;
        this.spectral0 = spectral0;
        this.spectral1 = spectral1;
        this.magnitudeFloatBits = magnitudeFloatBits;
        this.raPmFloatBits = raPmFloatBits;
        this.decPmFloatBits = decPmFloatBits;
        this.hd = hd;
        this.dm = dm;
        this.gc = gc;
    }
}
