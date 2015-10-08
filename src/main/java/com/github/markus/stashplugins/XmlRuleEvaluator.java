package com.github.markus.stashplugins;

import java.io.*;
import java.util.*;

import javax.xml.parsers.*;

import org.apache.commons.lang3.StringUtils;
import org.w3c.dom.*;
import org.xml.sax.*;

import static org.w3c.dom.Node.ELEMENT_NODE;
import static org.w3c.dom.Node.TEXT_NODE;

import com.atlassian.stash.setting.Settings;
import com.atlassian.stash.setting.SettingsValidationErrors;

public class XmlRuleEvaluator
{
	private Settings _settings;
	
	public XmlRuleEvaluator(Settings settings)
	{
		_settings = settings;
	}
	
	public String validatePermissions(List<String> paths, String branch, String user, List<String> userGroups)
	{
		String xml = _settings.getString("xmlExpression");
		
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		dbf.setNamespaceAware(false);
	    dbf.setValidating(false);
	    dbf.setIgnoringComments(true);
	    
	    Document doc = null;
	    
		try {
			DocumentBuilder db = dbf.newDocumentBuilder();
			InputSource is = new InputSource(new StringReader(xml));
			doc = db.parse(is);
		} catch (ParserConfigurationException e) {
			throw new RuntimeException(e);
		} catch (SAXException e) {
			throw new RuntimeException(e);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		return applyXmlRules(doc, paths, branch, user, userGroups);
	}
	
	private String applyXmlRules(Document doc, List<String> paths, String branch, String user, List<String> userGroups)
	{
		return applyDenyConditions(doc.getFirstChild(), paths, branch, user, userGroups);
	}
	
	private String applyDenyConditions(Node node, List<String> paths, String branch, String user, List<String> userGroups)
	{
		List<Node> denyConditionNodes = getChildElements(node);
		
		for (Node denyConditionNode : denyConditionNodes)
		{
			Node condition = getChildElements(denyConditionNode).get(0);
			if (applyCondition(condition, paths, branch, user, userGroups))
			{
				Node msgNode = denyConditionNode.getAttributes().getNamedItem("message");
				return msgNode != null ? msgNode.getNodeValue() : "";
			}
		}
		
		return null;
	}
	
	private boolean applyCondition(Node node, List<String> paths, String branch, String user, List<String> userGroups)
	{
		String name = node.getNodeName();
		if (name.equals("WhenAll"))
		{
			return applyWhenAllOperator(node, paths, branch, user, userGroups);
		}
		else if (name.equals("WhenAny"))
		{
			return applyWhenAnyOperator(node, paths, branch, user, userGroups);
		}
		else if (name.equals("NotWhenAll"))
		{
			return !applyWhenAllOperator(node, paths, branch, user, userGroups);
		}
		else if (name.equals("NotWhenAny"))
		{
			return !applyWhenAnyOperator(node, paths, branch, user, userGroups);
		}
		else if (name.equals("Match"))
		{
			return applyMatchFunction(node, paths, branch, user, userGroups);
		}
		else
		{
			throw new RuntimeException("Unknown element: " + name);
		}
	}
	
	private boolean applyWhenAllOperator(Node node, List<String> paths, String branch, String user, List<String> userGroups)
	{
		List<Node> conditions = getChildElements(node);
		for (Node condition : conditions)
		{
			if (!applyCondition(condition, paths, branch, user, userGroups))
				return false;
		}
		
		return true;
	}
	
	private boolean applyWhenAnyOperator(Node node, List<String> paths, String branch, String user, List<String> userGroups)
	{
		List<Node> conditions = getChildElements(node);
		for (Node condition : conditions)
		{
			if (applyCondition(condition, paths, branch, user, userGroups))
				return true;
		}
		
		return false;
	}

	private boolean applyMatchFunction(Node node, List<String> paths, String branch, String user, List<String> userGroups)
	{
		String pattern = node.getFirstChild().getNodeValue();
		String value = node.getAttributes().getNamedItem("value").getNodeValue();
		Node typeNode = node.getAttributes().getNamedItem("type");
		String type = typeNode != null ? typeNode.getNodeValue() : "exact";
		
		if (type.equals("glob"))
		{
			pattern = Utils.convertGlobToRegex(pattern);
		}
		
		if (value.equals("paths"))
		{
			for (String path : paths)
			{
				if (matcher(type, pattern, path))
					return true;
			}
		}
		else if (value.equals("branch"))
		{
			return matcher(type, pattern, branch);
		}
		else if (value.equals("user"))
		{
			return matcher(type, pattern, user);
		}
		else if (value.equals("groups"))
		{
			for (String group : userGroups)
			{
				if (matcher(type, pattern, group))
					return true;
			}
		}
		else
		{
			throw new RuntimeException("Unknown match value: " + value);
		}
		
		return false;
	}
	
	private boolean matcher(String type, String pattern, String value)
	{
		if (type.equals("exact"))
		{
			return value.equals(pattern);
		}
		else
		{
			return value.matches(pattern);
		}
	}
	
	public void validateSettings(SettingsValidationErrors settingsValidationErrors)
	{
		String xml = _settings.getString("xmlExpression", "");
		
		if (StringUtils.isEmpty(xml)) 
		{
            settingsValidationErrors.addFieldError("xmlExpression", "XML Expression is mandatory field");
            return;
        }
				
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		dbf.setNamespaceAware(false);
	    dbf.setValidating(false);
	    dbf.setIgnoringComments(true);
	    
	    Document doc = null;
	    MyErrorHandler errHandler = new MyErrorHandler();
	    
	    ArrayList<String> errors = new ArrayList<String>();
		
		try {
			DocumentBuilder db = dbf.newDocumentBuilder();
			db.setErrorHandler(errHandler);
			InputSource is = new InputSource(new StringReader(xml));
			doc = db.parse(is);
		} catch (ParserConfigurationException e) {
			errors.add("ParserConfigurationException: " + e.getMessage());
		} catch (SAXException e) {
			errors.add("SAXException: " + e.getMessage());
		} catch (IOException e) {
			errors.add("IOException: " + e.getMessage());
		}
		
		errors.addAll(errHandler.getErrors());
		
		if (!errors.isEmpty())
		{
			for (String error : errors)
			{
				settingsValidationErrors.addFieldError("xmlExpression", error);
			}
			
			return;
		}
		
		if (!validateXml(doc, settingsValidationErrors))
			settingsValidationErrors.addFieldError("xmlExpression", "Xml validation failed");
	}
	
	private boolean validateXml(Document doc, SettingsValidationErrors settingsValidationErrors)
	{
		Node root = doc.getFirstChild();
		if (!root.getNodeName().equals("AuthRules"))
		{
			settingsValidationErrors.addFieldError("xmlExpression", "Xml must contain single AuthRules root element");
			return false;
		}
		
		return validateNodesRecursively(root, settingsValidationErrors, 0);
	}
	
	private boolean validateNodesRecursively(Node node, SettingsValidationErrors settingsValidationErrors, int level)
	{
		List<Node> children = getChildElements(node);
		
		if (level == 0)
		{
			for (Node child : children)
			{
				if (!child.getNodeName().equals("DenyCondition"))
				{
					settingsValidationErrors.addFieldError("xmlExpression", "AuthRules must only contain DenyCondition elements");
					return false;
				}
				
				List<Node> denyCondChildren = getChildElements(child);
				if (denyCondChildren.size() != 1)
				{
					settingsValidationErrors.addFieldError("xmlExpression", "DenyCondition must contain single child element");
					return false;
				}
			}
		}
		else
		{
			for (Node child : children)
			{
		        if (!validateConditionNode(child, settingsValidationErrors))
		        	return false;
			}
		}
		
		level = level + 1;
		for (Node child : children)
		{
			if (!validateNodesRecursively(child, settingsValidationErrors, level))
				return false;
		}
		
		return true;
	}
	
	private boolean validateConditionNode(Node node, SettingsValidationErrors settingsValidationErrors)
	{
		String name = node.getNodeName();
		if (name.equals("WhenAll") || name.equals("WhenAny") || name.equals("NotWhenAll") || name.equals("NotWhenAny"))
		{
			return validateWhenOperator(node, settingsValidationErrors);
		}
		else if (name.equals("Match"))
		{
			return validateMatchFunction(node, settingsValidationErrors);
		}
		else
		{
			settingsValidationErrors.addFieldError("xmlExpression", "Invalid condition element name: " + name);
			return false;
		}
	}
	
	private boolean validateWhenOperator(Node node, SettingsValidationErrors settingsValidationErrors)
	{
		String name = node.getNodeName();
		List<Node> children = getChildElements(node);
		if (children.size() == 0)
		{
			settingsValidationErrors.addFieldError("xmlExpression", name + " must have atleast one child elements");
			return false;
		}
		
		return true;
	}
	
	private boolean validateMatchFunction(Node node, SettingsValidationErrors settingsValidationErrors)
	{
		Node valueNode = node.getAttributes().getNamedItem("value");
		String value = valueNode != null ? valueNode.getNodeValue() : "";
		if (!value.equals("paths") && !value.equals("branch") && !value.equals("user") && !value.equals("groups"))
		{
			settingsValidationErrors.addFieldError("xmlExpression", "Invalid match value: " + value);
			return false;
		}
		
		Node typeNode = node.getAttributes().getNamedItem("type");
		String type = typeNode != null ? typeNode.getNodeValue() : "exact";
		if (!type.equals("glob") && !type.equals("regex") && !type.equals("exact"))
		{
			settingsValidationErrors.addFieldError("xmlExpression", "Invalid match type: " + type);
			return false;
		}
		
		Node textNode = node.getFirstChild();
		if (textNode == null || textNode.getNodeValue() == null || textNode.getNodeValue().length() == 0)
		{
			settingsValidationErrors.addFieldError("xmlExpression", "Match element must contain pattern string");
			return false;
		}
		
		return true;
	}
	
	private List<Node> getChildElements(Node node)
	{
		ArrayList<Node> children = new ArrayList<Node>();
		
		NodeList nodeList = node.getChildNodes();
		for (int i = 0; i < nodeList.getLength(); i++) 
		{
			Node child = nodeList.item(i);
			if (child.getNodeType() == ELEMENT_NODE)
			{
				children.add(child);
			}
		}
		
		return children;
	}

	private class MyErrorHandler implements ErrorHandler 
	{
		private ArrayList<String> errors = new ArrayList<String>();

	    private String getParseExceptionInfo(SAXParseException spe) 
	    {
	        String systemId = spe.getSystemId();
	        if (systemId == null) {
	            systemId = "null";
	        }

	        String info = "URI=" + systemId + " Line=" + spe.getLineNumber() +
	                      ": " + spe.getMessage();
	        return info;
	    }

	    public void warning(SAXParseException spe) throws SAXException 
	    {
	    	errors.add("Warning: " + getParseExceptionInfo(spe));
	    }
	        
	    public void error(SAXParseException spe) throws SAXException 
	    {
	    	errors.add("Error: " + getParseExceptionInfo(spe));
	    }

	    public void fatalError(SAXParseException spe) throws SAXException 
	    {
	    	errors.add("Fatal Error: " + getParseExceptionInfo(spe));
	    }
	    
	    public List<String> getErrors()
	    {
	    	return errors;
	    }
	}
}
