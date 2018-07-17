package org.gsc.core.operator;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import org.gsc.core.Wallet;
import org.gsc.core.wrapper.AccountCapsule;
import org.gsc.core.wrapper.AssetIssueCapsule;
import org.gsc.core.wrapper.TransactionResultCapsule;
import org.gsc.core.wrapper.utils.TransactionUtil;
import org.gsc.config.Parameter.ChainConstant;
import org.gsc.db.AssetIssueStore;
import org.gsc.db.Manager;
import org.gsc.core.exception.ContractExeException;
import org.gsc.core.exception.ContractValidateException;
import org.gsc.protos.Contract.AccountUpdateContract;
import org.gsc.protos.Contract.UpdateAssetContract;
import org.gsc.protos.Protocol.Transaction.Result.code;

@Slf4j
public class UpdateAssetOperator extends AbstractOperator {

  UpdateAssetOperator(Any contract, Manager dbManager) {
    super(contract, dbManager);
  }

  @Override
  public boolean execute(TransactionResultCapsule ret) throws ContractExeException {
    long fee = calcFee();
    try {
      final UpdateAssetContract updateAssetContract = this.contract
          .unpack(UpdateAssetContract.class);

      long newLimit = updateAssetContract.getNewLimit();
      long newPublicLimit = updateAssetContract.getNewPublicLimit();
      byte[] ownerAddress = updateAssetContract.getOwnerAddress().toByteArray();
      ByteString newUrl = updateAssetContract.getUrl();
      ByteString newDescription = updateAssetContract.getDescription();

      AssetIssueStore assetIssueStore = dbManager.getAssetIssueStore();
      AccountCapsule accountCapsule = dbManager.getAccountStore().get(ownerAddress);
      AssetIssueCapsule assetIssueCapsule =
          assetIssueStore.get(accountCapsule.getAssetIssuedName().toByteArray());

      assetIssueCapsule.setFreeAssetNetLimit(newLimit);
      assetIssueCapsule.setPublicFreeAssetNetLimit(newPublicLimit);
      assetIssueCapsule.setUrl(newUrl);
      assetIssueCapsule.setDescription(newDescription);
      assetIssueStore.put(assetIssueCapsule.createDbKey(), assetIssueCapsule);

      ret.setStatus(fee, code.SUCESS);
    } catch (InvalidProtocolBufferException e) {
      logger.debug(e.getMessage(), e);
      ret.setStatus(fee, code.FAILED);
      throw new ContractExeException(e.getMessage());
    }

    return true;
  }

  @Override
  public boolean validate() throws ContractValidateException {

    if (this.contract == null) {
      throw new ContractValidateException("No contract!");
    }
    if (this.dbManager == null) {
      throw new ContractValidateException("No dbManager!");
    }
    if (!this.contract.is(UpdateAssetContract.class)) {
      throw new ContractValidateException(
          "contract type error,expected type [UpdateAssetContract],real type[" + contract
              .getClass() + "]");
    }
    final UpdateAssetContract updateAssetContract;
    try {
      updateAssetContract = this.contract.unpack(UpdateAssetContract.class);
    } catch (InvalidProtocolBufferException e) {
      logger.debug(e.getMessage(), e);
      throw new ContractValidateException(e.getMessage());
    }

    long newLimit = updateAssetContract.getNewLimit();
    long newPublicLimit = updateAssetContract.getNewPublicLimit();
    byte[] ownerAddress = updateAssetContract.getOwnerAddress().toByteArray();
    ByteString newUrl = updateAssetContract.getUrl();
    ByteString newDescription = updateAssetContract.getDescription();

    if (!Wallet.addressValid(ownerAddress)) {
      throw new ContractValidateException("Invalid ownerAddress");
    }

    AccountCapsule account = dbManager.getAccountStore().get(ownerAddress);
    if (account == null) {
      throw new ContractValidateException("Account has not existed");
    }

    if (account.getAssetIssuedName().isEmpty()) {
      throw new ContractValidateException("Account has not issue any asset");
    }

    assert (dbManager.getAssetIssueStore().get(account.getAssetIssuedName().toByteArray()) != null);

    if (!TransactionUtil.validUrl(newUrl.toByteArray())) {
      throw new ContractValidateException("Invalid url");
    }

    if (!TransactionUtil.validAssetDescription(newDescription.toByteArray())) {
      throw new ContractValidateException("Invalid description");
    }

    if (newLimit < 0 || newLimit >= ChainConstant.ONE_DAY_NET_LIMIT) {
      throw new ContractValidateException("Invalid FreeAssetNetLimit");
    }

    if (newPublicLimit < 0 || newPublicLimit >= ChainConstant.ONE_DAY_NET_LIMIT) {
      throw new ContractValidateException("Invalid PublicFreeAssetNetLimit");
    }

    return true;
  }

  @Override
  public ByteString getOwnerAddress() throws InvalidProtocolBufferException {
    return contract.unpack(AccountUpdateContract.class).getOwnerAddress();
  }

  @Override
  public long calcFee() {
    return 0;
  }
}
