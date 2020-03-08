package net.frontuari.model;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;

import org.compiere.acct.Doc;
import org.compiere.model.I_M_ProductionPlan;
import org.compiere.model.MAcctSchema;
import org.compiere.model.MClient;
import org.compiere.model.MDocType;
import org.compiere.model.MPeriod;
import org.compiere.model.MProduct;
import org.compiere.model.MProduction;
import org.compiere.model.MProductionLine;
import org.compiere.model.MProductionLineMA;
import org.compiere.model.MProductionPlan;
import org.compiere.model.MSysConfig;
import org.compiere.model.ModelValidationEngine;
import org.compiere.model.ModelValidator;
import org.compiere.model.PO;
import org.compiere.model.Query;
import org.compiere.process.DocAction;
import org.compiere.util.AdempiereUserError;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.compiere.util.Util;

public class FTUMProduction extends MProduction {

	/**
	 * 
	 */
	private static final long serialVersionUID = 8926621246925337411L;

	public FTUMProduction(Properties ctx, int M_Production_ID, String trxName) {
		super(ctx, M_Production_ID, trxName);
	}
	
	public FTUMProduction(Properties ctx, ResultSet rs, String trxName) {
		super(ctx, rs, trxName);
	}
	
	@Override
	public String prepareIt() {
		if (log.isLoggable(Level.INFO)) log.info(toString());
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this, ModelValidator.TIMING_BEFORE_PREPARE);
		if (m_processMsg != null)
			return DocAction.STATUS_Invalid;

		//	Std Period open?
		MPeriod.testPeriodOpen(getCtx(), getMovementDate(), MDocType.DOCBASETYPE_MaterialProduction, getAD_Org_ID());

		if ( getIsCreated().equals("N") )
		{
			m_processMsg = "Not created";
			return DocAction.STATUS_Invalid; 
		}

		if (!isUseProductionPlan()) {
			m_processMsg = validateEndProduct(getM_Product_ID());			
			if (!Util.isEmpty(m_processMsg)) {
				return DocAction.STATUS_Invalid;
			}
		} else {
			Query planQuery = new Query(getCtx(), I_M_ProductionPlan.Table_Name, "M_ProductionPlan.M_Production_ID=?", get_TrxName());
			List<MProductionPlan> plans = planQuery.setParameters(getM_Production_ID()).list();
			for(MProductionPlan plan : plans) {
				m_processMsg = validateEndProduct(plan.getM_Product_ID());
				if (!Util.isEmpty(m_processMsg)) {
					return DocAction.STATUS_Invalid;
				}
			}
		}

		m_processMsg = ModelValidationEngine.get().fireDocValidate(this, ModelValidator.TIMING_AFTER_PREPARE);
		if (m_processMsg != null)
			return DocAction.STATUS_Invalid;

		m_justPrepared = true;
		if (!DOCACTION_Complete.equals(getDocAction()))
			setDocAction(DOCACTION_Complete);
		return DocAction.STATUS_InProgress;
	}
	
	@Override
	protected String validateEndProduct(int M_Product_ID) {
		String msg = isBom(M_Product_ID);
		if (!Util.isEmpty(msg))
			return msg;

		if (!costsOK(M_Product_ID)) {
			msg = "Excessive difference in standard costs";
			if (MSysConfig.getBooleanValue(MSysConfig.MFG_ValidateCostsDifferenceOnCreate, false, getAD_Client_ID())) {
				return msg;
			} else {
				log.warning(msg);
			}
		}

		return null;
	}
	
	@Override
	protected boolean costsOK(int M_Product_ID) throws AdempiereUserError {
		MProduct product = MProduct.get(getCtx(), M_Product_ID);
		String costingMethod=product.getCostingMethod(MClient.get(getCtx()).getAcctSchema());
		// will not work if non-standard costing is used
		if (MAcctSchema.COSTINGMETHOD_StandardCosting.equals(costingMethod))
		{			
			String sql = "SELECT ABS(((cc.currentcostprice-(SELECT SUM(c.currentcostprice*bom.qtybom)"
					+ " FROM m_cost c"
					+ " INNER JOIN pp_product_bom bom ON (c.m_product_id=bom.m_product_id)"
					+ " INNER JOIN pp_product_bomline boml ON (bom.pp_product_bom_id = boml.pp_product_bom_id ) "
					+ " INNER JOIN m_costelement ce ON (c.m_costelement_id = ce.m_costelement_id AND ce.costingmethod = 'S')"
					+ " WHERE bom.m_product_id = pp.m_product_id)"
					+ " )/cc.currentcostprice))"
					+ " FROM m_product pp"
					+ " INNER JOIN m_cost cc on (cc.m_product_id=pp.m_product_id)"
					+ " INNER JOIN m_costelement ce ON (cc.m_costelement_id=ce.m_costelement_id)"
					+ " WHERE cc.currentcostprice > 0 AND pp.M_Product_ID = ?"
					+ " AND ce.costingmethod='S'";

			BigDecimal costPercentageDiff = DB.getSQLValueBD(get_TrxName(), sql, M_Product_ID);

			if (costPercentageDiff == null)
			{
				costPercentageDiff = Env.ZERO;
				String msg = "Could not retrieve costs";
				if (MSysConfig.getBooleanValue(MSysConfig.MFG_ValidateCostsOnCreate, false, getAD_Client_ID())) {
					throw new AdempiereUserError(msg);
				} else {
					log.warning(msg);
				}
			}

			if ( (costPercentageDiff.compareTo(new BigDecimal("0.005")))< 0 )
				return true;

			return false;
		}
		else if (MAcctSchema.COSTINGMETHOD_AverageInvoice.equals(costingMethod))
		{			
			String sql = "SELECT ABS(((cc.currentcostprice-(SELECT SUM(c.currentcostprice*boml.qtybom)"
					+ " FROM m_cost c"
					+ " INNER JOIN pp_product_bom bom ON (c.m_product_id=bom.m_product_id)"
					+ " INNER JOIN pp_product_bomline boml ON (bom.pp_product_bom_id = boml.pp_product_bom_id ) "
					+ " INNER JOIN m_costelement ce ON (c.m_costelement_id = ce.m_costelement_id AND ce.costingmethod = 'I')"
					+ " WHERE bom.m_product_id = pp.m_product_id)"
					+ " )/cc.currentcostprice))"
					+ " FROM m_product pp"
					+ " INNER JOIN m_cost cc on (cc.m_product_id=pp.m_product_id)"
					+ " INNER JOIN m_costelement ce ON (cc.m_costelement_id=ce.m_costelement_id)"
					+ " WHERE cc.currentcostprice > 0 AND pp.M_Product_ID = ?"
					+ " AND ce.costingmethod='I'";

			BigDecimal costPercentageDiff = DB.getSQLValueBD(get_TrxName(), sql, M_Product_ID);

			if (costPercentageDiff == null)
			{
				costPercentageDiff = Env.ZERO;
				String msg = "Could not retrieve costs";
				if (MSysConfig.getBooleanValue(MSysConfig.MFG_ValidateCostsOnCreate, false, getAD_Client_ID())) {
					throw new AdempiereUserError(msg);
				} else {
					log.warning(msg);
				}
			}

			if ( (costPercentageDiff.compareTo(new BigDecimal("0.005")))< 0 )
				return true;

			return false;
		}
		return true;
	}
	
	@Override
	protected String isBom(int M_Product_ID)
	{
		String bom = DB.getSQLValueString(get_TrxName(), "SELECT isbom FROM M_Product WHERE M_Product_ID = ?", M_Product_ID);
		if ("N".compareTo(bom) == 0)
		{
			return "Attempt to create product line for Non Bill Of Materials";
		}
		int materials = DB.getSQLValue(get_TrxName(), "SELECT count(PP_Product_BOM_ID) FROM PP_Product_BOM WHERE M_Product_ID = ?", M_Product_ID);
		if (materials == 0)
		{
			return "Attempt to create product line for Bill Of Materials with no BOM Products";
		}
		return null;
	}
	
	@Override
	public boolean reverseCorrectIt() 
	{
		if (log.isLoggable(Level.INFO)) log.info(toString());
		// Before reverseCorrect
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this,ModelValidator.TIMING_BEFORE_REVERSECORRECT);
		if (m_processMsg != null)
			return false;

		FTUMProduction reversal = reverse(false);
		if (reversal == null)
			return false;

		// After reverseCorrect
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this,ModelValidator.TIMING_AFTER_REVERSECORRECT);
		if (m_processMsg != null)
			return false;

		m_processMsg = reversal.getDocumentNo();

		return true;
	}
	
	protected FTUMProduction reverse(boolean accrual) {
		Timestamp reversalDate = accrual ? Env.getContextAsDate(getCtx(), "#Date") : getMovementDate();
		if (reversalDate == null) {
			reversalDate = new Timestamp(System.currentTimeMillis());
		}

		MPeriod.testPeriodOpen(getCtx(), reversalDate, Doc.DOCTYPE_MatProduction, getAD_Org_ID());
		FTUMProduction reversal = null;
		reversal = copyFrom (reversalDate);

		StringBuilder msgadd = new StringBuilder("{->").append(getDocumentNo()).append(")");
		reversal.addDescription(msgadd.toString());
		reversal.setReversal_ID(getM_Production_ID());
		reversal.saveEx(get_TrxName());
		
		// Reverse Line Qty
		MProductionLine[] sLines = getLines();
		MProductionLine[] tLines = reversal.getLines();
		for (int i = 0; i < sLines.length; i++)
		{		
			//	We need to copy MA
			if (sLines[i].getM_AttributeSetInstance_ID() == 0)
			{
				MProductionLineMA mas[] = MProductionLineMA.get(getCtx(), sLines[i].get_ID(), get_TrxName());
				for (int j = 0; j < mas.length; j++)
				{
					MProductionLineMA ma = new MProductionLineMA (tLines[i],
						mas[j].getM_AttributeSetInstance_ID(),
						mas[j].getMovementQty().negate(),mas[j].getDateMaterialPolicy());
					ma.saveEx(get_TrxName());					
				}
			}
		}

		if (!reversal.processIt(DocAction.ACTION_Complete))
		{
			m_processMsg = "Reversal ERROR: " + reversal.getProcessMsg();
			return null;
		}

		reversal.closeIt();
		reversal.setProcessing (false);
		reversal.setDocStatus(DOCSTATUS_Reversed);
		reversal.setDocAction(DOCACTION_None);
		reversal.saveEx(get_TrxName());

		msgadd = new StringBuilder("(").append(reversal.getDocumentNo()).append("<-)");
		addDescription(msgadd.toString());

		setProcessed(true);
		setReversal_ID(reversal.getM_Production_ID());
		setDocStatus(DOCSTATUS_Reversed);	//	may come from void
		setDocAction(DOCACTION_None);		

		return reversal;
	}
	
	protected FTUMProduction copyFrom(Timestamp reversalDate) {
		FTUMProduction to = new FTUMProduction(getCtx(), 0, get_TrxName());
		PO.copyValues (this, to, getAD_Client_ID(), getAD_Org_ID());

		to.set_ValueNoCheck ("DocumentNo", null);
		//
		to.setDocStatus (DOCSTATUS_Drafted);		//	Draft
		to.setDocAction(DOCACTION_Complete);
		to.setMovementDate(reversalDate);
		to.setIsComplete(false);
		to.setIsCreated("Y");
		to.setProcessing(false);
		to.setProcessed(false);
		to.setIsUseProductionPlan(isUseProductionPlan());
		if (isUseProductionPlan()) {
			to.saveEx();
			Query planQuery = new Query(Env.getCtx(), I_M_ProductionPlan.Table_Name, "M_ProductionPlan.M_Production_ID=?", get_TrxName());
			List<MProductionPlan> fplans = planQuery.setParameters(getM_Production_ID()).list();
			for(MProductionPlan fplan : fplans) {
				MProductionPlan tplan = new MProductionPlan(getCtx(), 0, get_TrxName());
				PO.copyValues (fplan, tplan, getAD_Client_ID(), getAD_Org_ID());
				tplan.setM_Production_ID(to.getM_Production_ID());
				tplan.setProductionQty(fplan.getProductionQty().negate());
				tplan.setProcessed(false);
				tplan.saveEx();

				MProductionLine[] flines = fplan.getLines();
				for(MProductionLine fline : flines) {
					MProductionLine tline = new MProductionLine(tplan);
					PO.copyValues (fline, tline, getAD_Client_ID(), getAD_Org_ID());
					tline.setM_ProductionPlan_ID(tplan.getM_ProductionPlan_ID());
					tline.setMovementQty(fline.getMovementQty().negate());
					tline.setPlannedQty(fline.getPlannedQty().negate());
					tline.setQtyUsed(fline.getQtyUsed().negate());
					tline.saveEx();
				}
			}
		} else {
			to.setProductionQty(getProductionQty().negate());	
			to.saveEx();
			MProductionLine[] flines = getLines();
			for(MProductionLine fline : flines) {
				MProductionLine tline = new MProductionLine(to);
				PO.copyValues (fline, tline, getAD_Client_ID(), getAD_Org_ID());
				tline.setM_Production_ID(to.getM_Production_ID());
				tline.setMovementQty(fline.getMovementQty().negate());
				tline.setPlannedQty(fline.getPlannedQty().negate());
				tline.setQtyUsed(fline.getQtyUsed().negate());
				tline.saveEx();
			}
		}

		return to;
	}
	
}
