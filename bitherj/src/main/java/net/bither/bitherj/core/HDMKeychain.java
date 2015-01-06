package net.bither.bitherj.core;

import net.bither.bitherj.crypto.EncryptedData;
import net.bither.bitherj.crypto.KeyCrypterException;
import net.bither.bitherj.crypto.hd.DeterministicKey;
import net.bither.bitherj.crypto.hd.HDKeyDerivation;
import net.bither.bitherj.crypto.mnemonic.MnemonicCode;
import net.bither.bitherj.crypto.mnemonic.MnemonicException;
import net.bither.bitherj.db.AbstractDb;
import net.bither.bitherj.utils.Utils;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Created by zhouqi on 15/1/3.
 */
public class HDMKeychain {
    public static interface HDMFetchRemotePublicKeys{
        void completeRemotePublicKeys(CharSequence password, ArrayList<HDMAddress.Pubs> partialPubs);
    }

    public static interface HDMFetchRemoteAddresses {
        List<HDMAddress.Pubs> getRemoteExistsPublicKeys(CharSequence password);
    }

    private transient byte[] seed;

    private ArrayList<HDMAddress> addresses;

    private int hdSeedId;
    private boolean isFromXRandom;

    // Create With Random
    public HDMKeychain(SecureRandom random, CharSequence password) {
        isFromXRandom = random.getClass().getCanonicalName().indexOf("XRandom") >= 0;
        seed = new byte[64];
        random.nextBytes(seed);
        EncryptedData encryptedSeed = new EncryptedData(seed, password);
        wipeSeed();
        hdSeedId = AbstractDb.addressProvider.addHDKey(encryptedSeed.toString(), isFromXRandom);
        addresses = new ArrayList<HDMAddress>();
    }

    // From DB
    public HDMKeychain(int seedId) {
        this.hdSeedId = seedId;
        addresses = new ArrayList<HDMAddress>();
        initFromDb();
    }

    // Import
    public HDMKeychain(EncryptedData encryptedSeed, boolean isFromXRandom ,CharSequence password, HDMFetchRemoteAddresses fetchDelegate) throws HDMBitherIdNotMatchException, MnemonicException.MnemonicLengthException {
        seed = encryptedSeed.decrypt(password);
        addresses = new ArrayList<HDMAddress>();
        List<HDMAddress.Pubs> pubs = fetchDelegate.getRemoteExistsPublicKeys(password);
        if(pubs.size() > 0){
            try {
                DeterministicKey root = externalChainRoot(password);
                byte[] pubDerived = root.deriveSoftened(0).getPubKey();
                byte[] pubFetched = pubs.get(0).hot;
                root.wipe();
                if(!Arrays.equals(pubDerived, pubFetched)){
                    wipeSeed();
                    throw new HDMBitherIdNotMatchException();
                }
            } catch (MnemonicException.MnemonicLengthException e) {
                wipeSeed();
                throw e;
            }
        }
        wipeSeed();
        ArrayList<HDMAddress> as = new ArrayList<HDMAddress>();
        for (HDMAddress.Pubs p : pubs) {
            as.add(new HDMAddress(p, false, this));
        }
        this.hdSeedId = AbstractDb.addressProvider.addHDKey(encryptedSeed.toString(), isFromXRandom);
        AbstractDb.addressProvider.completeHDMAddresses(getHdSeedId(), as);
        addresses.addAll(as);
    }

    public int prepareAddresses(int count, CharSequence password, byte[] coldExternalRootPub){
        DeterministicKey externalRootHot;
        DeterministicKey externalRootCold = HDKeyDerivation.createMasterPubKeyFromExtendedBytes(coldExternalRootPub);

        try {
            externalRootHot = externalChainRoot(password);
            externalRootHot.clearPrivateKey();
        } catch (MnemonicException.MnemonicLengthException e) {
            return 0;
        }
        ArrayList<HDMAddress.Pubs> pubs = new ArrayList<HDMAddress.Pubs>();
        for (int i = AbstractDb.addressProvider.maxHDMAddressPubIndex(getHdSeedId()) + 1;
             pubs.size() < count;
             i++) {
            HDMAddress.Pubs p = new HDMAddress.Pubs();
            try {
                p.hot = externalRootHot.deriveSoftened(i).getPubKey();
                p.cold = externalRootCold.deriveSoftened(i).getPubKey();
                pubs.add(p);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        AbstractDb.addressProvider.prepareHDMAddresses(getHdSeedId(), pubs);
        if (externalRootHot != null) {
            externalRootHot.wipe();
        }
        if (externalRootCold != null) {
            externalRootCold.wipe();
        }
        return pubs.size();
    }

    public List<HDMAddress> completeAddresses(int count, CharSequence password, HDMFetchRemotePublicKeys fetchDelegate) {
        int uncompletedAddressCount = uncompletedAddressCount();
        if(uncompletedAddressCount < count){
            throw new RuntimeException("Not enough uncompleted addresses " + count + "/" + uncompletedAddressCount + " : " + getHdSeedId());
        }
        ArrayList<HDMAddress> as = new ArrayList<HDMAddress>();
        synchronized (addresses) {
            ArrayList<HDMAddress.Pubs> pubs = AbstractDb.addressProvider.getUncompletedHDMAddressPubs(getHdSeedId(), count);
            try {
                fetchDelegate.completeRemotePublicKeys(password, pubs);
                for (HDMAddress.Pubs p : pubs) {
                    as.add(new HDMAddress(p, false, this));
                }
                AbstractDb.addressProvider.completeHDMAddresses(getHdSeedId(), as);
            } catch (Exception e) {
                e.printStackTrace();
                return as;
            }
            addresses.addAll(as);
        }
        return as;
    }

    public List<HDMAddress> getAddresses() {
        synchronized (addresses) {
            return addresses;
        }
    }

    private DeterministicKey externalChainRoot(CharSequence password) throws MnemonicException.MnemonicLengthException {
        DeterministicKey master = masterKey(password);
        DeterministicKey purpose = master.deriveHardened(44);
        DeterministicKey coinType = purpose.deriveHardened(0);
        DeterministicKey account = coinType.deriveHardened(0);
        DeterministicKey external = account.deriveSoftened(0);
        master.wipe();
        purpose.wipe();
        coinType.wipe();
        account.wipe();
        return external;
    }

    private DeterministicKey masterKey(CharSequence password) throws MnemonicException.MnemonicLengthException {
        MnemonicCode mnemonic = MnemonicCode.instance();
        decryptSeed(password);
        byte[] s = mnemonic.toSeed(mnemonic.toMnemonic(seed), "");
        wipeSeed();
        DeterministicKey master = HDKeyDerivation.createMasterPrivateKey(s);
        Utils.wipeBytes(s);
        return master;
    }

    public DeterministicKey getExternalKey(int index, CharSequence password){
        try {
            DeterministicKey externalChainRoot = externalChainRoot(password);
            DeterministicKey key = externalChainRoot.deriveSoftened(index);
            externalChainRoot.wipe();
            return key;
        }catch (Exception e){
            throw new RuntimeException(e);
        }
    }

    public int getCurrentMaxAddressIndex(){
        synchronized (addresses) {
            int max = Integer.MIN_VALUE;
            for (HDMAddress address : addresses) {
                if (address.getIndex() > max) {
                    max = address.getIndex();
                }
            }
            return max;
        }
    }

    private void initFromDb(){
        isFromXRandom = AbstractDb.addressProvider.isHDSeedFromXRandom(getHdSeedId());
        initAddressesFromDb();
    }

    private void initAddressesFromDb(){
        synchronized (addresses){
            List<HDMAddress> addrs = AbstractDb.addressProvider.getHDMAddressInUse(this);
            addresses.addAll(addrs);
        }
    }

    public int getHdSeedId(){
        return hdSeedId;
    }

    public int uncompletedAddressCount(){
        return AbstractDb.addressProvider.uncompletedHDMAddressCount(getHdSeedId());
    }

    public boolean isFromXRandom(){
        return isFromXRandom;
    }

    public void decryptSeed(CharSequence password) throws KeyCrypterException{
        if(hdSeedId <= 0){
            return;
        }
        String encrypted = AbstractDb.addressProvider.getEncryptSeed(hdSeedId);
        if(!Utils.isEmpty(encrypted)){
            seed = new EncryptedData(encrypted).decrypt(password);
        }
    }

    public List<String> getSeedWords(CharSequence password) throws MnemonicException.MnemonicLengthException {
        decryptSeed(password);
        List<String> words = MnemonicCode.instance().toMnemonic(seed);
        wipeSeed();
        return words;
    }

    public void wipeSeed(){
        Utils.wipeBytes(seed);
    }


    public static final class HDMBitherIdNotMatchException extends RuntimeException{
        public static final String msg = "HDM BitherId Not Match";

        public HDMBitherIdNotMatchException(){
            super(msg);
        }
    }
}
