package net.frontuari.model;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;

import org.compiere.model.I_M_ProductionPlan;
import org.compiere.model.MAcctSchema;
import org.compiere.model.MClient;
import org.compiere.model.MDocType;
import org.compiere.model.MPeriod;
import org.compiere.model.MProduct;
import org.compiere.model.MProduction;
import org.compiere.model.MProductionPlan;
import org.compiere.model.MSysConfig;
import org.compiere.model.ModelValidationEngine;
import org.compiere.model.ModelValidator;
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
	
}
