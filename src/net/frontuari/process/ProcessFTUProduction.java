package net.frontuari.process;

import org.compiere.model.MProduction;
import org.compiere.process.SvrProcess;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.compiere.util.Msg;
import org.libero.model.MPPOrder;
import org.libero.model.MPPOrderBOMLine;

import net.frontuari.model.FTUMProduction;

public class ProcessFTUProduction extends SvrProcess {

	@Override
	protected void prepare() {
		System.out.println("Hola");
	}

	@Override
	protected String doIt() throws Exception {
		if(getRecord_ID() <= 0)
			return "@Error@: " + Msg.getMsg(getCtx(), "M_Production_ID");
		
		
		int PPOrderID = DB.getSQLValue(get_TrxName(), "SELECT PP_Order_ID FROM M_Production WHERE M_Production_ID = ?",getRecord_ID());
		//	When has PP Order call FTUProduction
		if(PPOrderID > 0)
		{
			FTUMProduction production = new FTUMProduction(getCtx(), getRecord_ID(), get_TrxName());
			try {
				if(production.processIt(production.getDocAction())) {
					production.saveEx(get_TrxName());
					if(production.getDocStatus().equals(MProduction.DOCSTATUS_Reversed))
					{
						MPPOrder order = new MPPOrder(getCtx(), PPOrderID, get_TrxName());
						order.setDocStatus(MPPOrder.DOCSTATUS_Completed);
						order.setDocAction(MPPOrder.ACTION_Close);
						order.setDescription("");
						order.setQtyOrdered(order.getQtyEntered());
						order.saveEx(get_TrxName());
						for(MPPOrderBOMLine line : order.getLines())
						{
							line.setQtyEntered(line.getQtyDelivered());
							line.setQtyDelivered(Env.ZERO);
							line.saveEx(get_TrxName());
						}
					}
					return "Produccion: " + production.getDocumentNo() + " Procesada Satisfactoriamente! - " + production.getDocStatus();
				} else {
					production.saveEx(get_TrxName());
					return "@Error@: No se pudo Procesar Produccion: " + production.getDocumentNo() + " - " + production.getProcessMsg() + " - " + production.getDocStatus();
				}
			} catch (Exception ex) {
				return "@Error@: No se pudo Procesar Produccion: " + production.getDocumentNo() + " - " + production.getProcessMsg() + " - " + ex.getMessage() + " - " + production.getDocStatus();
			}
		}
		else
		{
			MProduction production = new MProduction(getCtx(), getRecord_ID(), get_TrxName());
			try {
				if(production.processIt(production.getDocAction())) {
					production.saveEx(get_TrxName());
					return "Produccion: " + production.getDocumentNo() + " Procesada Satisfactoriamente! - " + production.getDocStatus();
				} else {
					production.saveEx(get_TrxName());
					return "@Error@: No se pudo Procesar Produccion: " + production.getDocumentNo() + " - " + production.getProcessMsg() + " - " + production.getDocStatus();
				}
			} catch (Exception ex) {
				return "@Error@: No se pudo Procesar Produccion: " + production.getDocumentNo() + " - " + production.getProcessMsg() + " - " + ex.getMessage() + " - " + production.getDocStatus();
			}
		}
	}

}
