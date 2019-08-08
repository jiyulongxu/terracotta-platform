/*
 * Copyright (c) 2011-2019 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA, and/or its subsidiaries and/or its affiliates and/or their licensors.
 * Use, reproduction, transfer, publication or disclosure is prohibited except as specifically provided for in your License Agreement with Software AG.
 */

package com.terracottatech.migration.xml;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringWriter;
import java.util.Optional;

public class XmlUtility {

  public static Optional<String> getAttributeValue(Node node, String attributeName) {
    String retValue = null;
    NamedNodeMap attributeMap = node.getAttributes();
    for (int k = 0; k < attributeMap.getLength(); k++) {
      Node attribute = attributeMap.item(k);
      if (attributeName.equals(attribute.getLocalName())) {
        retValue = attribute.getNodeValue();
        break;
      }
    }
    return retValue == null ? Optional.empty() : Optional.of(retValue);
  }

  public static Node createSimpleTextNode(Node documentRoot, String nameSpace, String nodeName, String nodeText) {
    Element element = ((Document) documentRoot).createElementNS(nameSpace, nodeName);
    element.setTextContent(nodeText);
    return element;
  }

  public static Element createNode(Node documentRoot, String nameSpace, String nodeName) {
    return ((Document) documentRoot).createElementNS(nameSpace, nodeName);
  }

  public static Node getClonedParentDocFromRootNode(Node rootNode) {
    Node parentNode = rootNode.getParentNode();
    return parentNode.cloneNode(true);
  }

  public static String getPrettyPrintableXmlString(Node doc) throws Exception {
    Transformer transformer = TransformerFactory.newInstance().newTransformer();
    transformer.setOutputProperty(OutputKeys.INDENT, "yes");
    transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
    transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
    //initialize StreamResult with File object to save to file
    StreamResult result = new StreamResult(new StringWriter());
    DOMSource source = new DOMSource(doc);
    transformer.transform(source, result);
    return result.getWriter().toString();
  }

}