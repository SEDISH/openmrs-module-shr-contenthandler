/**
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */
package org.openmrs.module.shr.contenthandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Concept;
import org.openmrs.ConceptComplex;
import org.openmrs.ConceptDescription;
import org.openmrs.ConceptName;
import org.openmrs.Encounter;
import org.openmrs.EncounterRole;
import org.openmrs.EncounterType;
import org.openmrs.Obs;
import org.openmrs.Patient;
import org.openmrs.Provider;
import org.openmrs.api.ConceptService;
import org.openmrs.api.ObsService;
import org.openmrs.api.context.Context;
import org.openmrs.module.shr.contenthandler.api.CodedValue;
import org.openmrs.module.shr.contenthandler.api.Content;
import org.openmrs.module.shr.contenthandler.api.ContentHandler;
import org.openmrs.obs.ComplexData;
import org.openmrs.util.OpenmrsConstants;

/**
 * A content handler for storing data as unstructured <i>blobs</i>.
 */
public class UnstructuredDataHandler implements ContentHandler {
	
	protected final Log log = LogFactory.getLog(this.getClass());
	
	protected static final String UNSTRUCTURED_ATTACHMENT_CONCEPT_BASE_NAME = "Unstructured Attachment";
	protected static final String UNSTRUCTURED_DATA_HANDLER_GLOBAL_PROP = "shr.contenthandler.unstructureddatahandler.key";
	
	protected final String contentType;
	protected final CodedValue typeCode;
	protected final CodedValue formatCode;
	
	
	/**
	 * Construct a new unstructured data handler that references content via content type
	 */
	public UnstructuredDataHandler(String contentType) {
		this.contentType = contentType;
		typeCode = formatCode = null;
	}
	
	/**
	 * Construct a new unstructured data handler that references content via type and format code
	 */
	public UnstructuredDataHandler(CodedValue typeCode, CodedValue formatCode) {
		this.typeCode = typeCode;
		this.formatCode = formatCode;
		this.contentType = null;
	}


	/**
	 * @see ContentHandler#saveContent(Patient, Provider, EncounterRole, EncounterType, Content)
	 * @should create a new encounter object using the current time
	 * @should contain a complex obs containing the content
	 */
	@Override
	public Encounter saveContent(Patient patient, Map<EncounterRole, Set<Provider>> providersByRole, EncounterType encounterType, Content content) {
		Encounter enc = createEncounter(patient, providersByRole, encounterType, content);
		Context.getEncounterService().saveEncounter(enc);
		return enc;
	}
	
	/**
	 * Create a new encounter object with a complex obs for storing the specified content. 
	 */
	private Encounter createEncounter(Patient patient, Map<EncounterRole, Set<Provider>> providersByRole, EncounterType encounterType, Content content) {
		Encounter enc = new Encounter();
		
		enc.setEncounterType(encounterType);
		Obs obs = createUnstructuredDataObs(content);
		obs.setPerson(patient);
		obs.setEncounter(enc);
		enc.addObs(obs);
		enc.setEncounterDatetime(obs.getObsDatetime());
		enc.setPatient(patient);
		
		// Add all providers to encounter
		for (EncounterRole role : providersByRole.keySet()) {
			Set<Provider> providers = providersByRole.get(role);
			for (Provider provider : providers) {
				enc.addProvider(role, provider);
			}
		}
		
		return enc;
	}
	
	private Obs createUnstructuredDataObs(Content content) {
		Obs res = new Obs();
		ComplexData cd = new ComplexData(buildTitle(), content);
		
		res.setConcept(getUnstructuredAttachmentConcept(content.getFormatCode()));
		res.setComplexData(cd);
		res.setObsDatetime(new Date());
		
		return res;
	}

	private Concept getUnstructuredAttachmentConcept(CodedValue formatCode) {
		ConceptService cs = Context.getConceptService();
		String conceptName = getUnstructuredAttachmentConceptName(formatCode);
		Concept res = cs.getConceptByName(conceptName);
		if (res==null) {
			res = buildUnstructuredAttachmentConcept(conceptName);
			cs.saveConcept(res);
		}
		return res;
	}
	
	private static String getUnstructuredAttachmentConceptName(CodedValue formatCode) {
		return String.format("%s (%s-%s)", UNSTRUCTURED_ATTACHMENT_CONCEPT_BASE_NAME, formatCode.getCodingScheme(), formatCode.getCode());
	}
	
	private Concept buildUnstructuredAttachmentConcept(String name) {
		ConceptService cs = Context.getConceptService();
		ConceptComplex c = new ConceptComplex();
		ConceptName cn = new ConceptName(name, Locale.ENGLISH);
		ConceptDescription cd = new ConceptDescription("Represents a generic unstructured data attachment", Locale.ENGLISH);
		
		c.setFullySpecifiedName(cn);
		c.setPreferredName(cn);
		c.addDescription(cd);
		c.setDatatype(cs.getConceptDatatypeByName("Complex"));
		c.setConceptClass(cs.getConceptClassByName("Misc"));
		
		String handlerKey = Context.getAdministrationService().getGlobalProperty(UNSTRUCTURED_DATA_HANDLER_GLOBAL_PROP);
		c.setHandler(handlerKey);
		
		return c;
	}
	
	

	/**
	 * @see ContentHandler#fetchContent(String)
	 * @should return a Content object for the encounter if found
	 * @should return null if the encounter doesn't contain an unstructured data obs
	 * @should return null if the encounter isn't found
	 */
	@Override
	public Content fetchContent(String encounterUuid) {
		Encounter enc = Context.getEncounterService().getEncounterByUuid(encounterUuid);
		if (enc==null)
			return null;
		
		List<Content> res = new LinkedList<Content>();
		getContentFromEncounter(res, enc);
		if (res.isEmpty())
			return null;
		return res.get(0);
	}

	/**
	 * @see ContentHandler#fetchContent(int)
	 * @should return a Content object for the encounter if found
	 * @should return null if the encounter doesn't contain an unstructured data obs
	 * @should return null if the encounter isn't found
	 */
	@Override
	public Content fetchContent(int encounterId) {
		Encounter enc = Context.getEncounterService().getEncounter(encounterId);
		if (enc==null)
			return null;
		
		List<Content> res = new LinkedList<Content>();
		getContentFromEncounter(res, enc);
		if (res.isEmpty())
			return null;
		return res.get(0);
	}


	/**
	 * @see ContentHandler#queryEncounters(Patient, Date, Date)
	 * @should return a list of Content objects for all matching encounters
	 * @should only return Content objects that match the handler's content type
	 * @should return an empty list if no encounters with unstructured data obs are found
	 * @should handle null values for date from and to
	 */
	@Override
	public List<Content> queryEncounters(Patient patient, Date from, Date to) {
		return queryEncounters(patient, null, from, to);
	}

	/**
	 * @see ContentHandler#queryEncounters(Patient, EncounterType, Date, Date)
	 * @should return a list of Content objects for all matching encounters
	 * @should only return Content objects that match the handler's content type
	 * @should return an empty list if no encounters with unstructured data obs are found
	 * @should handle null values for date from and to
	 */
	@Override
	public List<Content> queryEncounters(Patient patient, List<EncounterType> encounterTypes, Date from, Date to) {
		List<Encounter> encs = Context.getEncounterService().getEncounters(
			patient, null, from, to, null, encounterTypes, null, null, null, false
		);
		if (encs==null || encs.isEmpty())
			return Collections.emptyList();
		
		List<Content> res = new ArrayList<Content>(encs.size());
		
		for (Encounter enc : encs) {
			getContentFromEncounter(res, enc);
		}
		
		return res;
	}
	
	private void getContentFromEncounter(List<Content> dst, Encounter enc) {
		ObsService os = Context.getObsService();
		
		for (Obs obs : enc.getAllObs()) {
			if (obs.isComplex() && isConceptAnUnstructuredDataType(obs.getConcept())) {
				Obs complexObs = os.getComplexObs(obs.getObsId(), OpenmrsConstants.RAW_VIEW);
				Object data = complexObs.getComplexData()!=null ? complexObs.getComplexData().getData() : null;
				
				if (data==null || !(data instanceof Content)) {
					log.warn("Unprocessable content found in unstructured data obs (obsId = " + obs.getId() + ")");
					continue;
				}
				
				String contentTitle = contentType!=null ? (((Content)data).getContentType()) :
					buildTypeFormatCodeTitle(((Content)data).getTypeCode(), ((Content)data).getFormatCode());
					
				if (contentTitle.equals(buildTitle())) {
					dst.add((Content)data);
				}
			}
		}
	}
	
	private boolean isConceptAnUnstructuredDataType(Concept c) {
		return c.getName().getName().startsWith(UNSTRUCTURED_ATTACHMENT_CONCEPT_BASE_NAME);
	}
	
	/**
	 * Build a title that's suitable for referencing the complex obs
	 */
	private String buildTitle() {
		return contentType!=null ? contentType : buildTypeFormatCodeTitle(typeCode, formatCode);
	}
	
	protected static String buildTypeFormatCodeTitle(CodedValue typeCode, CodedValue formatCode) {
		//Use the formatCode code as the title
		return formatCode.getCode();
	}

	/**
	 * @see ContentHandler#cloneHandler()
	 * @should return an UnstructuredDataHandler instance with the same content type
	 */
	@Override
	public UnstructuredDataHandler cloneHandler() {
		if (contentType!=null) {
			return new UnstructuredDataHandler(contentType);
		} else {
			return new UnstructuredDataHandler(typeCode, formatCode);
		}
	}
}
