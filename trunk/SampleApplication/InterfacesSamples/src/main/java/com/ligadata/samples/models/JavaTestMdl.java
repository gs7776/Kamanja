package com.ligadata.samples.models;

import com.ligadata.FatafatBase.*;
import com.ligadata.samples.messages.*;

public class JavaTestMdl extends ModelBase {
	static JavaTestMdlObj objSignleton = new JavaTestMdlObj();

	public ModelResult execute(boolean emitAllResults) {
		return null;
	}

	public JavaTestMdl(TransactionContext inTxnContext) {
		super(new ModelContext(inTxnContext), objSignleton);
	}

	public static boolean IsValidMessage(MessageContainerBase msg) {
		return (msg instanceof CustAlertHistory);
	}

	public static ModelBase CreateNewModel(TransactionContext txnContext) {
		return new JavaTestMdl(txnContext);
	}

	public static String getModelName() {
		return "LowBalanceAlert";
	}

	public static String getVersion() {
		return "0.0.1";
	}
}