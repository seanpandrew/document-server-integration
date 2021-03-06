/*
 *
 * (c) Copyright Ascensio System Limited 2010-2017
 *
 * The MIT License (MIT)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
*/

package helpers;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLEncoder;
import java.text.MessageFormat;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;


public class ServiceConverter
{
    private static int ConvertTimeout = 120000;
    private static final String DocumentConverterUrl = ConfigManager.GetProperty("files.docservice.url.converter");
    private static final String DocumentStorageUrl = ConfigManager.GetProperty("files.docservice.url.storage");
    private static final MessageFormat ConvertParams = new MessageFormat("?url={0}&outputtype={1}&filetype={2}&title={3}&key={4}");
    private static final int MaxTry = 3;

    static
    {
        try
        {
            int timeout = Integer.parseInt(ConfigManager.GetProperty("files.docservice.timeout"));
            if(timeout > 0) 
            {
                ConvertTimeout = timeout;
            }
        }
        catch (Exception ex)
        {
        }
    }

    public static String GetConvertedUri(String documentUri, String fromExtension, String toExtension, String documentRevisionId, Boolean isAsync) throws Exception
    {
        String convertedDocumentUri = null;
        
        String xml = SendRequestToConvertService(documentUri, fromExtension, toExtension, documentRevisionId, isAsync);
        
        Document document = ConvertStringToXmlDocument(xml);

        Element responceFromConvertService = document.getDocumentElement();
        if (responceFromConvertService == null)
            throw new Exception("Invalid answer format");

        NodeList errorElement = responceFromConvertService.getElementsByTagName("Error");
        if (errorElement != null && errorElement.getLength() > 0)
            ProcessConvertServiceResponceError(Integer.parseInt(errorElement.item(0).getTextContent()));

        NodeList endConvertNode = responceFromConvertService.getElementsByTagName("EndConvert");
        if (endConvertNode == null || endConvertNode.getLength() == 0)
            throw new Exception("EndConvert node is null");
        
        Boolean isEndConvert = Boolean.parseBoolean(endConvertNode.item(0).getTextContent());
        
        NodeList percentNode = responceFromConvertService.getElementsByTagName("Percent");
        if (percentNode == null || percentNode.getLength() == 0)
            throw new Exception("Percent node is null");
        
        Integer percent = Integer.parseInt(percentNode.item(0).getTextContent());

        if (isEndConvert)
        {
            NodeList fileUrlNode = responceFromConvertService.getElementsByTagName("FileUrl");
            if (fileUrlNode == null || fileUrlNode.getLength() == 0)
                throw new Exception("FileUrl node is null");
            
            convertedDocumentUri = fileUrlNode.item(0).getTextContent();
            percent = 100;
        }
        else
        {
            percent = percent >= 100 ? 99 : percent;
        }

        return percent >= 100 ? convertedDocumentUri : "";
    }

    public static String GetExternalUri(InputStream fileStream, long contentLength, String contentType, String documentRevisionId) throws IOException, Exception
    {
        Object[] args = {"", "", "", "", documentRevisionId};
        
        String urlTostorage = DocumentStorageUrl + ConvertParams.format(args);

        URL url = new URL(urlTostorage);
        java.net.HttpURLConnection connection = (java.net.HttpURLConnection) url.openConnection();

        connection.setDoOutput(true);
        connection.setDoInput(true);
        connection.setInstanceFollowRedirects(false); 
        connection.setRequestMethod("POST"); 
        connection.setRequestProperty("Content-Type", contentType == null ? "application/octet-stream" : contentType);
        connection.setRequestProperty("charset", "utf-8");
        connection.setRequestProperty("Content-Length", Long.toString(contentLength));
        connection.setUseCaches (false);
               
        try (DataOutputStream dataOutputStream = new DataOutputStream(connection.getOutputStream())) {
            int read;
            final byte[] bytes = new byte[1024];
            while ((read = fileStream.read(bytes)) != -1) {
                dataOutputStream.write(bytes, 0, read);
            }
            
            dataOutputStream.flush();
        }
        
        InputStream stream = connection.getInputStream();

        if (stream == null)
        {
            throw new Exception("Could not get an answer");
        }
        
        String xml = ConvertStreamToString(stream);

        connection.disconnect();
        
        String res = GetResponseUri(xml);
          
        return res;
    }

    public static String GenerateRevisionId(String expectedKey)
    {
        if (expectedKey.length() > 20)
            expectedKey = Integer.toString(expectedKey.hashCode());
        
        String key = expectedKey.replace("[^0-9-.a-zA-Z_=]", "_");
        
        return key.substring(0, Math.min(key.length(), 20));
    }

    private static String SendRequestToConvertService(String documentUri, String fromExtension, String toExtension, String documentRevisionId, Boolean isAsync) throws Exception
    {
        fromExtension = fromExtension == null || fromExtension.isEmpty() ? FileUtility.GetFileExtension(documentUri) : fromExtension;

        String title = FileUtility.GetFileName(documentUri);
        title = title == null || title.isEmpty() ? UUID.randomUUID().toString() : title;
        
        documentRevisionId = documentRevisionId == null || documentRevisionId.isEmpty() ? documentUri : documentRevisionId;
        
        documentRevisionId = GenerateRevisionId(documentRevisionId);

        Object[] args = {
                            URLEncoder.encode(documentUri, java.nio.charset.StandardCharsets.UTF_8.toString()),
                            toExtension.replace(".", ""),
                            fromExtension.replace(".", ""),
                            title,
                            documentRevisionId
                        };
        
        String urlToConverter = DocumentConverterUrl +  ConvertParams.format(args);

        if (isAsync)
            urlToConverter += "&async=true";

        URL url = new URL(urlToConverter);
        java.net.HttpURLConnection connection = (java.net.HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(ConvertTimeout);
        
        InputStream stream = null;
        int countTry = 0;
        
        while (countTry < MaxTry)
        {
            try
            {
                countTry++;
                stream = connection.getInputStream();
                break;
            }
            catch (Exception ex)
            {
                if(!(ex instanceof TimeoutException))
                    throw new Exception("Bad Request");
            }
        }
        if (countTry == MaxTry)
        {
            throw new Exception("Timeout");
        }

        if (stream == null)
            throw new Exception("Could not get an answer");
               
        String xml = ConvertStreamToString(stream);

        connection.disconnect();
        
        return xml;
    }

    private static void ProcessConvertServiceResponceError(int errorCode) throws Exception
    {
        String errorMessage = "";
        String errorMessageTemplate = "Error occurred in the ConvertService: ";

        switch (errorCode)
        {
            case -8:
                errorMessage = errorMessageTemplate + "Error document VKey";
                break;
            case -7:
                errorMessage = errorMessageTemplate + "Error document request";
                break;
            case -6:
                errorMessage = errorMessageTemplate + "Error database";
                break;
            case -5:
                errorMessage = errorMessageTemplate + "Error unexpected guid";
                break;
            case -4:
                errorMessage = errorMessageTemplate + "Error download error";
                break;
            case -3:
                errorMessage = errorMessageTemplate + "Error convertation error";
                break;
            case -2:
                errorMessage = errorMessageTemplate + "Error convertation timeout";
                break;
            case -1:
                errorMessage = errorMessageTemplate + "Error convertation unknown";
                break;
            case 0:
                break;
            default:
                errorMessage = "ErrorCode = " + errorCode;
                break;
        }

        throw new Exception(errorMessage);
    }

    private static String GetResponseUri(String xml) throws Exception
    {
        Document document = ConvertStringToXmlDocument(xml);
        
        Element responceFromConvertService = document.getDocumentElement();
        if (responceFromConvertService == null)
            throw new Exception("Invalid answer format");

        NodeList errorElement = responceFromConvertService.getElementsByTagName("Error");
        if (errorElement != null && errorElement.getLength() > 0)
            ProcessConvertServiceResponceError(Integer.parseInt(errorElement.item(0).getTextContent()));

        NodeList endConvert = responceFromConvertService.getElementsByTagName("EndConvert");
        if (endConvert == null || endConvert.getLength() == 0)
            throw new Exception("Invalid answer format");
        
        Boolean isEndConvert = Boolean.parseBoolean(endConvert.item(0).getTextContent());

        int resultPercent = 0;
        String responseUri = null;
        
        if (isEndConvert)
        {
            NodeList fileUrl = responceFromConvertService.getElementsByTagName("FileUrl");
            if (fileUrl == null || endConvert.getLength() == 0)
                throw new Exception("Invalid answer format");

            resultPercent = 100;
            responseUri = fileUrl.item(0).getTextContent();
        }
        else
        {
            NodeList percent = responceFromConvertService.getElementsByTagName("Percent");
            if (percent != null && percent.getLength() > 0)
                resultPercent = Integer.parseInt(percent.item(0).getTextContent());
            
            resultPercent = resultPercent >= 100 ? 99 : resultPercent;
        }

        return resultPercent >= 100 ? responseUri : "";
    }

    private static String ConvertStreamToString(InputStream stream) throws IOException
    {
        InputStreamReader inputStreamReader = new InputStreamReader(stream);
        StringBuilder stringBuilder = new StringBuilder();
        BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
        String line = bufferedReader.readLine();

        while(line != null) {
            stringBuilder.append(line);
            line =bufferedReader.readLine();
        }

        String result = stringBuilder.toString();
        
        return result;
    }

    private static Document ConvertStringToXmlDocument(String xml) throws IOException, ParserConfigurationException, SAXException
    {
        DocumentBuilderFactory documentBuildFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder doccumentBuilder = documentBuildFactory.newDocumentBuilder();
        InputStream inputStream = new ByteArrayInputStream(xml.getBytes("utf-8"));
        InputSource inputSource = new InputSource(inputStream);
        Document document = doccumentBuilder.parse(inputSource);
        
        return document;
    }
}