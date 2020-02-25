/******************************************************************************
  * Product: Adempiere ERP & CRM Smart Business Solution                       *
 * This program is free software; you can redistribute it and/or modify it    *
 * under the terms version 2 of the GNU General Public License as published   *
 * by the Free Software Foundation. This program is distributed in the hope   *
 * that it will be useful, but WITHOUT ANY WARRANTY; without even the implied *
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.           *
 * See the GNU General Public License for more details.                       *
 * You should have received a copy of the GNU General Public License along    *
 * with this program; if not, write to the Free Software Foundation, Inc.,    *
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA.                     *
 * For the text or an alternative of this public license, you may reach us    *
 * Copyright (C) 2003-2007 e-Evolution,SC. All Rights Reserved.               *
 * Contributor(s): Victor Perez www.e-evolution.com                           *
 *                 Teo Sarca, www.arhipac.ro                                  *
 *****************************************************************************/
package org.libero.process;


import java.sql.Timestamp;
import java.util.logging.Level;

import org.adempiere.exceptions.AdempiereException;
import org.adempiere.exceptions.FillMandatoryException;
import org.compiere.model.MDocType;
import org.compiere.model.MMovement;
import org.compiere.model.MMovementLine;
import org.compiere.model.MQuery;
import org.compiere.model.MStorageReservation;
import org.compiere.model.MTable;
import org.compiere.model.PrintInfo;
import org.compiere.print.MPrintFormat;
import org.compiere.print.ReportCtl;
import org.compiere.print.ReportEngine;
import org.compiere.process.ClientProcess;
import org.compiere.process.ProcessInfoParameter;
import org.compiere.process.SvrProcess;
import org.libero.model.MPPOrder;
import org.libero.model.MPPOrderBOMLine;

/**
 * Complete & Print Manufacturing Order
 * @author victor.perez@e-evolution.com
 * @author Teo Sarca, www.arhipac.ro
 */
public class CompletePrintOrder extends SvrProcess
implements ClientProcess
{
	/** The Order */
	private int p_PP_Order_ID = 0;
	private boolean p_IsPrintPickList = false;
	private boolean p_IsPrintWorkflow = false;
	@SuppressWarnings("unused")
	private boolean p_IsPrintPackList = false; // for future use
	private boolean p_IsComplete = false;

	/**
	 * Prepare - e.g., get Parameters.
	 */
	protected void prepare()
	{
		for (ProcessInfoParameter para : getParameter())
		{
			String name = para.getParameterName();
			if (para.getParameter() == null);
			else if (name.equals("PP_Order_ID"))
				p_PP_Order_ID = para.getParameterAsInt(); 
			else if (name.equals("IsPrintPickList"))
				p_IsPrintPickList = para.getParameterAsBoolean();				
			else if (name.equals("IsPrintWorkflow"))
				p_IsPrintWorkflow = para.getParameterAsBoolean();
			else if (name.equals("IsPrintPackingList"))
				p_IsPrintPackList = para.getParameterAsBoolean();
			else if (name.equals("IsComplete"))
				p_IsComplete = para.getParameterAsBoolean(); 
			else
				log.log(Level.SEVERE, "prepare - Unknown Parameter: " + name);
		}
		
		
	} // prepare

	/**
	 * Perform process.
	 * 
	 * @return Message (clear text)
	 * @throws Exception
	 *             if not successful
	 */
	protected String doIt() throws Exception
	{

		if (p_PP_Order_ID == 0)
		{
			throw new FillMandatoryException(MPPOrder.COLUMNNAME_PP_Order_ID);
		}

		if (p_IsComplete)
		{
			
			MPPOrder order = new MPPOrder(getCtx(), p_PP_Order_ID, get_TrxName());
			if (!order.isAvailable())
			{
				throw new AdempiereException("@NoQtyAvailable@");
			}
			
			if(	order.isProductWithOutQty()) {
				String fields = order.crititalProductsWithOutInventory(order.get_ID());
				throw new AdempiereException("Los siguientes productos: "+fields+"son criticos y no tienen Inventario, para poder realizar la Operacion dichos productos deben tener Existencia **");
			}
			//
			// Process document
			boolean ok = order.processIt(MPPOrder.DOCACTION_Complete);
			order.saveEx(get_TrxName());
			if (!ok)
			{
				throw new AdempiereException(order.getProcessMsg());
			}
			
			//	Added by Jorge Colmenarez 2020-02-24 16:05
			//	Create Inventory Movement it's automatic by DocType selected
			if(order.get_ValueAsBoolean("IsMovementAutomatic"))
			{
				createMovement(order);
			}
			//	End Jorge Colmenarez
			
			//
			// Document Status should be completed
			if (!MPPOrder.DOCSTATUS_Completed.equals(order.getDocStatus()))
			{
				throw new AdempiereException(order.getProcessMsg());
			}
		}

		if (p_IsPrintPickList)
		{
			// Get Format & Data
			ReportEngine re = this.getReportEngine("Manufacturing_Order_BOM_Header ** TEMPLATE **","PP_Order_BOM_Header_v");
			if(re == null )
			{
				return "";
			}
			ReportCtl.preview(re);
			re.print(); // prints only original
		}
		if (p_IsPrintPackList)
		{
			// Get Format & Data
			ReportEngine re = this.getReportEngine("Manufacturing_Order_BOM_Header_Packing ** TEMPLATE **","PP_Order_BOM_Header_v");
			if(re == null )
			{
				return "";
			}
			ReportCtl.preview(re);
			re.print(); // prints only original
		}
		if (p_IsPrintWorkflow)
		{
			// Get Format & Data
			ReportEngine re = this.getReportEngine("Manufacturing_Order_Workflow_Header ** TEMPLATE **","PP_Order_Workflow_Header_v");
			if(re == null )
			{
				return "";
			}
			ReportCtl.preview(re);
			re.print(); // prints only original
		}

		return "@OK@";

	} // doIt
	
	/*
	 * get the a Report Engine Instance using the view table 
	 * @param tableName
	 */
	private ReportEngine getReportEngine(String formatName, String tableName)
	{
		// Get Format & Data
		int format_id= MPrintFormat.getPrintFormat_ID(formatName, MTable.getTable_ID(tableName), getAD_Client_ID());
		MPrintFormat format = MPrintFormat.get(getCtx(), format_id, true);
		if (format == null)
		{
			addLog("@NotFound@ @AD_PrintFormat_ID@");
			return null;
		}
		// query
		MQuery query = new MQuery(tableName);
		query.addRestriction("PP_Order_ID", MQuery.EQUAL, p_PP_Order_ID);
		// Engine
		PrintInfo info = new PrintInfo(tableName,  MTable.getTable_ID(tableName), p_PP_Order_ID);
		ReportEngine re = new ReportEngine(getCtx(), format, query, info);
		return re;
	}
	
	/**
	 * Create Inventory Movement when it's automatic selection
	 * @autor Carlos Vargas, cvargas@frontuari.net
	 * @param order PP_Order object
	 */
	private void createMovement(MPPOrder order)
	{
		// Para guardar el anterior Localizador
		int tmp_locator = 0;
		/* Para guardar el anterior encabezado */
		MMovement tmp_m_movement = null;
		
		for(MPPOrderBOMLine line : order.getLines()) {
			
			if(!line.get_ValueAsBoolean("IsDerivative") && !line.get_ValueAsBoolean("IsRacking")) {
				
				if(order.isProductWithInventory(line.getM_Product_ID(),order.get_ID())) {
					
					// si es diferente del anterior crea una nueva cabezera...
					if(tmp_locator != line.getM_Locator_ID()) {
						//	get DocType
						MDocType dt = new MDocType(getCtx(), order.getC_DocType_ID(), get_TrxName());
						
						// crea una nueva cabezera 
						MMovement m_movement = new MMovement(getCtx(), 0, get_TrxName());
						m_movement.setAD_Org_ID(order.getAD_Org_ID());
						m_movement.setMovementDate(new Timestamp(System.currentTimeMillis()));
						m_movement.setC_DocType_ID(dt.get_ValueAsInt("C_DocTypeMovement_ID"));
						m_movement.setIsApproved(false);
						m_movement.setIsInTransit(false);
						m_movement.saveEx(get_TrxName());
						
						// guarda el objecto del movimiento
						tmp_m_movement = m_movement;
						
						// crea una nueva linea 
						MMovementLine m_movement_line = new MMovementLine(m_movement);
						m_movement_line.setAD_Org_ID(line.getAD_Org_ID());
						m_movement_line.setLine(line.getLine());
						m_movement_line.setM_Product_ID(line.getM_Product_ID());
						m_movement_line.setM_Locator_ID(line.get_ValueAsInt("M_LocatorFrom_ID"));
						m_movement_line.setM_LocatorTo_ID(line.getM_Locator_ID());
						m_movement_line.setMovementQty(line.getQtyRequired());
						m_movement_line.saveEx(get_TrxName());
						
					}else {
						// si igual al anterior agrega una linea mas
						MMovementLine m_movement_line = new MMovementLine(tmp_m_movement);
						m_movement_line.setAD_Org_ID(line.getAD_Org_ID());
						m_movement_line.setLine(line.getLine());
						m_movement_line.setM_Product_ID(line.getM_Product_ID());
						m_movement_line.setM_Locator_ID(line.get_ValueAsInt("M_LocatorFrom_ID"));
						m_movement_line.setM_LocatorTo_ID(line.getM_Locator_ID());
						m_movement_line.setMovementQty(line.getQtyRequired());
						m_movement_line.saveEx(get_TrxName());	
					}
					//Enviar la misma orden de manufactura
					if(!tmp_m_movement.processIt(MMovement.DOCACTION_Prepare))
					{
						throw new AdempiereException(tmp_m_movement.getProcessMsg());
					}
					
					tmp_m_movement.set_ValueOfColumn("PP_Order_ID", order.get_ID());
					tmp_m_movement.saveEx(get_TrxName());
					
					tmp_locator = line.getM_Locator_ID();
			
				}
			}
		}
	}
	
	
} // CompletePrintOrder
