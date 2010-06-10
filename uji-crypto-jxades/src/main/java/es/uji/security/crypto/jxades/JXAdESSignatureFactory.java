package es.uji.security.crypto.jxades;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collections;

import javax.xml.crypto.MarshalException;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureException;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.spec.XPathFilterParameterSpec;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import net.java.xades.security.xml.XAdES.SignaturePolicyIdentifier;
import net.java.xades.security.xml.XAdES.SignaturePolicyIdentifierImpl;
import net.java.xades.security.xml.XAdES.XAdES;
import net.java.xades.security.xml.XAdES.XAdES_EPES;
import net.java.xades.security.xml.XAdES.XMLAdvancedSignature;
import net.java.xades.util.XMLUtils;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import es.uji.security.crypto.ISignFormatProvider;
import es.uji.security.crypto.SignatureOptions;
import es.uji.security.crypto.SignatureResult;
import es.uji.security.crypto.config.ConfigManager;
import es.uji.security.crypto.config.OS;
import es.uji.security.util.i18n.LabelManager;

public class JXAdESSignatureFactory implements ISignFormatProvider
{
    public SignatureResult formatSignature(SignatureOptions signatureOptions) throws Exception
    {
        byte[] data = OS.inputStreamToByteArray(signatureOptions.getDataToSign());
        X509Certificate certificate = signatureOptions.getCertificate();
        PrivateKey privateKey = signatureOptions.getPrivateKey();

        ByteArrayInputStream originalData = new ByteArrayInputStream(data);

        SignatureResult signatureResult = new SignatureResult();

        if (certificate == null)
        {
            signatureResult.setValid(false);
            signatureResult.addError(LabelManager.get("ERROR_FACTURAE_NOCERT"));

            return signatureResult;
        }

        if (privateKey == null)
        {
            signatureResult.setValid(false);
            signatureResult.addError(LabelManager.get("ERROR_FACTURAE_NOKEY"));

            return signatureResult;
        }

        // Load XML data
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        Element element = db.parse(originalData).getDocumentElement();

        // Create a XAdES-EPES profile
        XAdES_EPES xades = (XAdES_EPES) XAdES.newInstance(XAdES.EPES, element);

        // SigningCertificate. Check the certificate validity (local)
        try
        {
            certificate.checkValidity();
        }
        catch (CertificateException cex)
        {
            signatureResult.setValid(false);
            signatureResult.addError(LabelManager.get("ERROR_CERTIFICATE_EXPIRED"));

            return signatureResult;
        }

        xades.setSigningCertificate(certificate);

        SignaturePolicyIdentifier spi;

        if (signatureOptions.getPolicyIdentifier() != null)
        {
            spi = new SignaturePolicyIdentifierImpl(false);
            spi.setIdentifier(signatureOptions.getPolicyIdentifier());
            spi.setDescription(signatureOptions.getPolicyDescription());
            xades.setSignaturePolicyIdentifier(spi);
        }
        /*
         * else { spi = new SignaturePolicyIdentifierImpl(true); }
         */

        ConfigManager conf = ConfigManager.getInstance();
        int tsaCount = conf.getIntProperty("DIGIDOC_TSA_COUNT", 0);

        String tsaUrl = null;

        if (tsaCount != 0)
        {
            tsaUrl = conf.getProperty("DIGIDOC_TSA1_URL");
        }

        // Sign data
        XMLAdvancedSignature xmlSignature = XMLAdvancedSignature.newInstance(xades);

        try
        {
            NodeList result = element.getElementsByTagNameNS("http://www.w3.org/2000/09/xmldsig#",
                    "Signature");
            int numSignature = result.getLength();

            String baseRef = "";
            if (signatureOptions.getXAdESBaseReference() != null)
            {
                baseRef = signatureOptions.getXAdESBaseReference();
            }

            // TODO: Revisar frima cosign, si no es enveloped funciona bien por el else.
            if (signatureOptions.isCoSignEnabled())
            {
                XMLSignatureFactory xmlSignatureFactory = XMLSignatureFactory.getInstance("DOM");
                DigestMethod digestMethod = xmlSignatureFactory.newDigestMethod(DigestMethod.SHA1,
                        null);

                Transform transform = xmlSignatureFactory.newTransform(Transform.XPATH,
                        new XPathFilterParameterSpec("not(ancestor-or-self::dsig:Signature)",
                                Collections.singletonMap("dsig", XMLSignature.XMLNS)));

                Reference reference = xmlSignatureFactory.newReference(baseRef, digestMethod,
                        Collections.singletonList(transform), null, null);

                xmlSignature.sign(certificate, privateKey, SignatureMethod.RSA_SHA1, Arrays
                        .asList(new Object[] { reference }), "S" + numSignature, tsaUrl);
            }
            else
            {
                xmlSignature.sign(certificate, privateKey, SignatureMethod.RSA_SHA1, Arrays
                        .asList(new Object[] { baseRef }), "S" + numSignature, tsaUrl);
            }
        }
        catch (MarshalException me)
        {
            signatureResult.setValid(false);
            signatureResult.addError(LabelManager.get("ERROR_FACTURAE_SIGNATURE"));

            return signatureResult;
        }
        catch (XMLSignatureException xmlse)
        {
            signatureResult.setValid(false);
            signatureResult.addError(LabelManager.get("ERROR_FACTURAE_SIGNATURE"));

            return signatureResult;
        }
        catch (GeneralSecurityException gse)
        {
            signatureResult.setValid(false);
            signatureResult.addError(LabelManager.get("ERROR_FACTURAE_SIGNATURE"));

            return signatureResult;
        }

        // Return Results
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BufferedOutputStream bos = new BufferedOutputStream(out);

        XMLUtils.writeXML(bos, xmlSignature.getBaseElement(), false);
        bos.flush();

        signatureResult.setValid(true);
        signatureResult.setSignatureData(new ByteArrayInputStream(out.toString().getBytes()));

        return signatureResult;
    }
}
